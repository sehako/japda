package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.repository.SaleInventoryCounterRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryReserveResult
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SaleInventoryCounterRepositoryImpl::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("판매 일정 재고 카운터 영속성")
class SaleInventoryCounterRepositoryTest {
	@Autowired
	private lateinit var repository: SaleInventoryCounterRepository

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	private var saleId: Long = 0

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		jdbcTemplate.update("DELETE FROM inventory_reservations")
		jdbcTemplate.update("DELETE FROM sale_inventory_counters")
		jdbcTemplate.update("DELETE FROM payments")
		jdbcTemplate.update("DELETE FROM orders")
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM sale_days")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
		val productId = jdbcTemplate.queryForObject(
			"INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '상품', 'READY', now()) RETURNING id",
			Long::class.java,
		)!!
		jdbcTemplate.update("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, 20, 1)", SALE_DATE)
		saleId = jdbcTemplate.queryForObject(
			"""INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at)
				VALUES (?, 1, ?, 35000, 10, now()) RETURNING id""",
			Long::class.java,
			productId,
			SALE_DATE,
		)!!
	}

	@Test
	@DisplayName("판매 일정 카운터를 점유 수량 0으로 생성한다")
	fun 판매_일정_카운터_점유_수량_0으로_생성한다() {
		repository.create(saleId, NOW)

		assertEquals(0, repository.findCommittedQuantity(saleId))
		assertEquals(
			NOW,
			jdbcTemplate.queryForObject(
				"SELECT created_at FROM sale_inventory_counters WHERE sale_id = ?",
				java.sql.Timestamp::class.java,
				saleId,
			)?.toInstant(),
		)
	}

	@Test
	@DisplayName("남은 수량 이내의 재고만 확보하고 경계 수량을 허용한다")
	fun 남은_수량_이내_재고만_확보하고_경계_수량_허용한다() {
		repository.create(saleId, NOW)

		assertEquals(SaleInventoryReserveResult.Acquired, repository.reserve(saleId, 4, NOW.plusSeconds(1)))
		assertEquals(SaleInventoryReserveResult.Acquired, repository.reserve(saleId, 6, NOW.plusSeconds(2)))
		assertEquals(SaleInventoryReserveResult.Insufficient(0), repository.reserve(saleId, 1, NOW.plusSeconds(3)))
		assertEquals(10, repository.findCommittedQuantity(saleId))
	}

	@Test
	@DisplayName("요청 수량만 부족하면 실제 양수 잔여 수량을 반환한다")
	fun 요청_수량만_부족하면_실제_양수_잔여_수량을_반환한다() {
		repository.create(saleId, NOW)
		assertEquals(SaleInventoryReserveResult.Acquired, repository.reserve(saleId, 7, NOW.plusSeconds(1)))

		assertEquals(SaleInventoryReserveResult.Insufficient(3), repository.reserve(saleId, 4, NOW.plusSeconds(2)))
		assertEquals(7, repository.findCommittedQuantity(saleId))
	}

	@Test
	@DisplayName("카운터 누락과 재고 부족을 서로 다른 결과로 반환한다")
	fun 카운터_누락과_재고_부족_서로_다른_결과() {
		assertEquals(SaleInventoryReserveResult.MissingCounter, repository.reserve(saleId, 1, NOW))
		repository.create(saleId, NOW)

		assertEquals(SaleInventoryReserveResult.Insufficient(10), repository.reserve(saleId, 0, NOW))
		assertEquals(SaleInventoryReserveResult.Insufficient(10), repository.reserve(saleId, Int.MAX_VALUE, NOW))
		assertEquals(0, repository.findCommittedQuantity(saleId))
	}

	@Test
	@DisplayName("점유 수량이 판매 수량을 초과하면 데이터 불변식 오류로 중단한다")
	fun 점유_수량이_판매_수량을_초과하면_데이터_불변식_오류로_중단한다() {
		repository.create(saleId, NOW)
		jdbcTemplate.update("UPDATE sale_inventory_counters SET committed_quantity = 11 WHERE sale_id = ?", saleId)

		val exception = assertFailsWith<IllegalStateException> {
			repository.reserve(saleId, 1, NOW.plusSeconds(1))
		}

		assertEquals("판매 일정의 잔여 재고가 유효 범위를 벗어났습니다.", exception.message)
	}

	@Test
	@DisplayName("점유 수량 범위 안에서만 재고를 반환한다")
	fun 점유_수량_범위_안에서만_재고_반환한다() {
		repository.create(saleId, NOW)
		repository.reserve(saleId, 4, NOW)

		assertTrue(repository.release(saleId, 3, NOW.plusSeconds(1)))
		assertFalse(repository.release(saleId, 2, NOW.plusSeconds(2)))
		assertFalse(repository.release(saleId, 0, NOW.plusSeconds(3)))
		assertEquals(1, repository.findCommittedQuantity(saleId))
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("동시 재고 확보 성공 수량은 최초 판매 수량을 넘지 않는다")
	fun 동시_재고_확보_성공_수량_최초_판매_수량_이하() {
		repository.create(saleId, NOW)

		val results = Executors.newFixedThreadPool(20).use { executor ->
			(1..20).map {
				executor.submit<SaleInventoryReserveResult> { repository.reserve(saleId, 1, NOW.plusSeconds(1)) }
			}.map { it.get(10, TimeUnit.SECONDS) }
		}

		assertEquals(10, results.count { it == SaleInventoryReserveResult.Acquired })
		assertEquals(10, results.count { it == SaleInventoryReserveResult.Insufficient(0) })
		assertEquals(10, repository.findCommittedQuantity(saleId))
	}

	private companion object {
		val SALE_DATE: LocalDate = LocalDate.parse("2026-09-11")
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
