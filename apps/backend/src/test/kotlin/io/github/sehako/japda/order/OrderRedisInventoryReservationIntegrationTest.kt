package io.github.sehako.japda.order

import io.github.sehako.japda.order.application.dto.CreateOrderDto
import io.github.sehako.japda.order.application.service.OrderService
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import io.github.sehako.japda.order.infrastructure.inventory.key.RedisInventoryKeyFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
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
		"product.image.s3.region=ap-northeast-2",
		"product.image.s3.bucket=test-product-images",
		"sale.daily-capacity=20",
	],
)
@Import(OrderRedisInventoryReservationIntegrationTest.FixedClockConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisplayName("PostgreSQL과 Redis 주문 재고 선점 통합")
class OrderRedisInventoryReservationIntegrationTest {
	@Autowired
	private lateinit var orderService: OrderService

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
	@DisplayName("Redis가 품절이면 판매 행 잠금을 기다리지 않고 DB transaction 전에 거절한다")
	fun Redis_품절_DB_transaction_전에_거절한다() {
		initializeStock(saleId, available = 0)

		dataSource.connection.use { connection ->
			connection.autoCommit = false
			connection.prepareStatement("SELECT id FROM sales WHERE id = ? FOR UPDATE").use { statement ->
				statement.setLong(1, saleId)
				statement.executeQuery().use { assertTrue(it.next()) }
			}

			val executor = Executors.newSingleThreadExecutor()
			try {
				val failure = assertFailsWith<ExecutionException> {
					executor.submit { orderService.create(createOrderDto(saleId, quantity = 1)) }
						.get(1, TimeUnit.SECONDS)
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
	@DisplayName("Redis 선점 뒤 DB가 주문을 거절하면 차감과 예약 토큰을 복원한다")
	fun Redis_선점_후_DB_거절_차감과_예약_토큰을_복원한다() {
		val missingSaleId = saleId + 10_000
		initializeStock(missingSaleId, available = 3)

		val exception = assertFailsWith<OrderException> {
			orderService.create(createOrderDto(missingSaleId, quantity = 2))
		}

		assertEquals(OrderErrorCode.SALE_NOT_FOUND, exception.errorCode)
		assertEquals("3", available(missingSaleId))
		assertTrue(reservationKeys(missingSaleId).isEmpty())
		assertEquals(0, orderCount())
	}

	@Test
	@Order(3)
	@DisplayName("Redis 선점 뒤 DB가 실제 품절을 확인하면 현재 generation을 폐기한다")
	fun DB_실제_품절_확인_현재_generation을_폐기한다() {
		insertCommittedOrder(saleId, quantity = 10)
		initializeStock(saleId, available = 5, generation = "stale-generation")

		val exception = assertFailsWith<OrderException> {
			orderService.create(createOrderDto(saleId, quantity = 1))
		}

		assertEquals(OrderErrorCode.QUANTITY_UNAVAILABLE, exception.errorCode)
		assertFalse(redisTemplate.hasKey(keyFactory.stock(saleId)))
		assertTrue(reservationKeys(saleId).isEmpty())
		assertEquals(1, orderCount())
	}

	@Test
	@Order(4)
	@DisplayName("같은 멱등 요청은 Redis 재고를 추가 차감하지 않는다")
	fun 같은_멱등_요청_Redis_재고를_추가_차감하지_않는다() {
		initializeStock(saleId, available = 10)
		val idempotencyKey = UUID.randomUUID()
		val request = createOrderDto(saleId, quantity = 2, idempotencyKey = idempotencyKey)

		val first = orderService.create(request)
		assertEquals("8", available(saleId))
		val second = orderService.create(request)

		assertEquals(first, second)
		assertEquals("8", available(saleId))
		assertEquals(1, orderCount())
	}

	@Test
	@Order(5)
	@DisplayName("Redis가 실제 DB 재고보다 많이 허용해도 동시 주문은 DB 최종 검증으로 초과 판매하지 않는다")
	fun Redis_과대_재고_동시_주문_DB_최종_검증으로_초과_판매를_방지한다() {
		initializeStock(saleId, available = 12)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val created = listOf(1L, 2L).map { buyerId ->
				executor.submit<Boolean> {
					try {
						orderService.create(createOrderDto(saleId, quantity = 6, buyerId = buyerId))
						true
					} catch (exception: OrderException) {
						if (exception.errorCode != OrderErrorCode.QUANTITY_UNAVAILABLE) throw exception
						false
					}
				}
			}.map { it.get(10, TimeUnit.SECONDS) }

			assertEquals(1, created.count { it })
			assertEquals(1, created.count { !it })
		} finally {
			executor.shutdownNow()
		}
		assertEquals(6, jdbcTemplate.queryForObject("SELECT COALESCE(sum(quantity), 0) FROM orders", Int::class.java))
	}

	@Test
	@Order(6)
	@DisplayName("Redis 장애가 발생하면 PostgreSQL 경로로 우회해 주문을 생성한다")
	fun Redis_장애_PostgreSQL_경로로_우회해_주문을_생성한다() {
		redis.dockerClient.pauseContainerCmd(redis.containerId).exec()

		val response = orderService.create(createOrderDto(saleId, quantity = 2))

		assertEquals(2, response.quantity)
		assertEquals(1, orderCount())
	}

	private fun initializeStock(saleId: Long, available: Int, generation: String = UUID.randomUUID().toString()) {
		redisTemplate.opsForHash<String, String>().putAll(
			keyFactory.stock(saleId),
			mapOf("generation" to generation, "available" to available.toString()),
		)
		redisTemplate.expire(keyFactory.stock(saleId), Duration.ofSeconds(30))
	}

	private fun available(saleId: Long): String? =
		redisTemplate.opsForHash<String, String>().get(keyFactory.stock(saleId), "available")

	private fun reservationKeys(saleId: Long): Set<String> =
		redisTemplate.keys("order-integration-test:inventory:{$saleId}:reservation:*")

	private fun orderCount(): Int =
		jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Int::class.java)!!

	private fun insertCommittedOrder(saleId: Long, quantity: Int) {
		val orderId = jdbcTemplate.queryForObject(
			"""INSERT INTO orders (
				sale_id, buyer_id, idempotency_key, payment_order_id, quantity, product_name, unit_price, total_price, status,
				recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at
			) VALUES (?, 999, ?, ?, ?, '기존 상품', 35000, ?, 'PENDING_PAYMENT', '홍길동', '010-1234-5678',
				'06236', '서울시 강남구', '101호', ?, ?) RETURNING id""",
			Long::class.java,
			saleId,
			UUID.randomUUID(),
			UUID.randomUUID().toString(),
			quantity,
			35_000L * quantity,
			java.sql.Timestamp.from(NOW.minusSeconds(60)),
			java.sql.Timestamp.from(NOW.plusSeconds(120)),
		)!!
		jdbcTemplate.update(
			"""INSERT INTO inventory_reservations
				(id, sale_id, order_id, quantity, status, expires_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'RESERVED', ?, ?, ?)""",
			UUID.randomUUID(),
			saleId,
			orderId,
			quantity,
			java.sql.Timestamp.from(NOW.plusSeconds(120)),
			java.sql.Timestamp.from(NOW.minusSeconds(60)),
			java.sql.Timestamp.from(NOW.minusSeconds(60)),
		)
		jdbcTemplate.update(
			"UPDATE sale_inventory_counters SET committed_quantity = ? WHERE sale_id = ?",
			quantity,
			saleId,
		)
	}

	private fun createOrderDto(
		saleId: Long,
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
