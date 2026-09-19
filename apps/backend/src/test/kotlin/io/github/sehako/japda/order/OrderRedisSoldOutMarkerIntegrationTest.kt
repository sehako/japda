package io.github.sehako.japda.order

import io.github.sehako.japda.order.application.dto.CreateOrderDto
import io.github.sehako.japda.order.application.inventory.SoldOutInventoryMarker
import io.github.sehako.japda.order.application.service.OrderService
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import io.github.sehako.japda.order.infrastructure.inventory.key.RedisInventoryKeyFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(
	properties = [
		"order.inventory.redis.enabled=true",
		"order.inventory.redis.namespace=order-integration-test",
		"order.inventory.redis.connect-timeout=200ms",
		"order.inventory.redis.command-timeout=200ms",
		"order.inventory.redis.sold-out-ttl=200ms",
		"product.image.s3.region=ap-northeast-2",
		"product.image.s3.bucket=test-product-images",
		"sale.daily-capacity=20",
	],
)
@Import(OrderRedisSoldOutMarkerIntegrationTest.FixedClockConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisplayName("PostgreSQL과 Redis 품절 마커 통합")
class OrderRedisSoldOutMarkerIntegrationTest {
	@Autowired
	private lateinit var orderService: OrderService

	@Autowired
	private lateinit var soldOutInventoryMarker: SoldOutInventoryMarker

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Autowired
	private lateinit var dataSource: DataSource

	@Autowired
	private lateinit var redisTemplate: StringRedisTemplate

	@Autowired
	private lateinit var keyFactory: RedisInventoryKeyFactory

	private var saleId: Long = 0

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		checkNotNull(redisTemplate.connectionFactory).connection.use { it.serverCommands().flushDb() }
		jdbcTemplate.update("DELETE FROM inventory_reservations")
		jdbcTemplate.update("DELETE FROM payments")
		jdbcTemplate.update("DELETE FROM orders")
		jdbcTemplate.update("DELETE FROM sale_inventory_counters")
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM sale_days")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")

		val productId = jdbcTemplate.queryForObject(
			"INSERT INTO products (seller_id, name, status, created_at) VALUES (1, 'Redis 통합 상품', 'READY', ?) RETURNING id",
			Long::class.java,
			java.sql.Timestamp.from(NOW),
		)!!
		jdbcTemplate.update("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, 20, 1)", SALE_DATE)
		saleId = jdbcTemplate.queryForObject(
			"""INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at)
				VALUES (?, 1, ?, 35000, 10, ?) RETURNING id""",
			Long::class.java,
			productId,
			SALE_DATE,
			java.sql.Timestamp.from(NOW),
		)!!
		jdbcTemplate.update(
			"INSERT INTO sale_inventory_counters (sale_id, committed_quantity, created_at, updated_at) VALUES (?, 0, ?, ?)",
			saleId,
			java.sql.Timestamp.from(NOW),
			java.sql.Timestamp.from(NOW),
		)
	}

	@AfterEach
	fun Redis_pause를_해제한다() {
		if (redis.isRunning && redis.containerId != null) {
			runCatching { redis.dockerClient.unpauseContainerCmd(redis.containerId).exec() }
		}
	}

	@Test
	@Order(1)
	fun `품절_마커_hit은_DB_transaction_시작_전에_주문을_거절한다`() {
		soldOutInventoryMarker.markSoldOut(saleId)

		dataSource.connection.use { connection ->
			connection.autoCommit = false
			connection.prepareStatement("SELECT id FROM sales WHERE id = ? FOR UPDATE").use { statement ->
				statement.setLong(1, saleId)
				statement.executeQuery().use { assertTrue(it.next()) }
			}
			val executor = Executors.newSingleThreadExecutor()
			try {
				val failure = assertFailsWith<ExecutionException> {
					executor.submit { orderService.create(createOrderDto(quantity = 1)) }.get(1, TimeUnit.SECONDS)
				}.cause
				assertTrue(failure is OrderException)
				assertEquals(OrderErrorCode.QUANTITY_UNAVAILABLE, failure.errorCode)
			} finally {
				connection.rollback()
				executor.shutdownNow()
			}
		}
		assertEquals(0, orderCount())
	}

	@Test
	@Order(2)
	fun `DB가_완전_품절을_확인하면_transaction_종료_후_마커를_기록한다`() {
		setCommittedQuantity(10)

		val exception = assertFailsWith<OrderException> { orderService.create(createOrderDto(quantity = 1)) }

		assertEquals(OrderErrorCode.QUANTITY_UNAVAILABLE, exception.errorCode)
		assertTrue(redisTemplate.hasKey(keyFactory.soldOut(saleId)))
		assertEquals(0, orderCount())
	}

	@Test
	@Order(3)
	fun `DB에_잔여_재고가_있으면_요청_수량이_부족해도_마커를_기록하지_않는다`() {
		setCommittedQuantity(8)

		val exception = assertFailsWith<OrderException> { orderService.create(createOrderDto(quantity = 3)) }

		assertEquals(OrderErrorCode.QUANTITY_UNAVAILABLE, exception.errorCode)
		assertFalse(redisTemplate.hasKey(keyFactory.soldOut(saleId)))
	}

	@Test
	@Order(4)
	fun `마커가_만료된_뒤_DB_재고가_반환되면_주문이_성공한다`() {
		setCommittedQuantity(10)
		assertFailsWith<OrderException> { orderService.create(createOrderDto(quantity = 1)) }
		setCommittedQuantity(8)

		Thread.sleep(300)
		val response = orderService.create(createOrderDto(quantity = 1))

		assertEquals(1, response.quantity)
		assertEquals(1, orderCount())
	}

	@Test
	@Order(5)
	fun `Redis_장애는_PostgreSQL_경로로_우회해_주문을_생성한다`() {
		redis.dockerClient.pauseContainerCmd(redis.containerId).exec()

		val response = orderService.create(createOrderDto(quantity = 2))

		assertEquals(2, response.quantity)
		assertEquals(1, orderCount())
	}

	private fun setCommittedQuantity(quantity: Int) {
		jdbcTemplate.update("UPDATE sale_inventory_counters SET committed_quantity = ? WHERE sale_id = ?", quantity, saleId)
	}

	private fun orderCount(): Int =
		jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Int::class.java)!!

	private fun createOrderDto(
		quantity: Int,
		buyerId: Long = 123,
		idempotencyKey: UUID = UUID.randomUUID(),
	) = CreateOrderDto(
		buyerId = buyerId,
		idempotencyKey = idempotencyKey,
		saleId = saleId,
		quantity = quantity,
		recipientName = "홍길동",
		phoneNumber = "010-1234-5678",
		postalCode = "06236",
		address = "서울시 강남구",
		detailAddress = "101호",
		deliveryMessage = "문 앞",
	)

	@TestConfiguration(proxyBeanMethods = false)
	class FixedClockConfiguration {
		@Bean
		@Primary
		fun fixedClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
	}

	private companion object {
		val SALE_DATE: LocalDate = LocalDate.parse("2026-09-11")
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")
		const val REDIS_PORT = 6379

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")

		@Container
		@JvmStatic
		val redis = GenericContainer("redis:7.4-alpine").withExposedPorts(REDIS_PORT)

		@DynamicPropertySource
		@JvmStatic
		fun redisProperties(registry: DynamicPropertyRegistry) {
			registry.add("order.inventory.redis.host", redis::getHost)
			registry.add("order.inventory.redis.port") { redis.getMappedPort(REDIS_PORT) }
		}
	}
}
