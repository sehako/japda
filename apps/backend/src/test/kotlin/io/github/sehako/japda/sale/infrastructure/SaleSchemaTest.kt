package io.github.sehako.japda.sale.infrastructure

import io.github.sehako.japda.PostgreSqlTestContainerConfiguration
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@Import(PostgreSqlTestContainerConfiguration::class)
@DisplayName("판매 PostgreSQL 스키마")
class SaleSchemaTest(
	@Autowired private val jdbcTemplate: JdbcTemplate,
	@Autowired transactionManager: PlatformTransactionManager,
) {
	private val transactionTemplate = TransactionTemplate(transactionManager)

	@AfterEach
	fun 정리() {
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
	}

	@Test
	@DisplayName("판매 저장_상품 ID와 판매 필드를 저장하고 판매자와 상태는 중복 저장하지 않는다")
	fun 판매_저장_상품_ID와_판매_필드를_저장하고_판매자와_상태는_중복_저장하지_않는다() {
		val productId = insertProduct()
		val startsAt = Instant.parse("2026-09-09T09:00:00Z")
		val endsAt = Instant.parse("2026-09-16T09:00:00Z")
		val createdAt = Instant.parse("2026-09-09T08:00:00Z")

		insertSale(productId, 10_000L, 100L, 100L, startsAt, endsAt, createdAt)

		val columns = jdbcTemplate.queryForList(
			"SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'sales'",
		).map { it.getValue("column_name") }
		val stored = jdbcTemplate.queryForMap(
			"SELECT product_id, price, initial_quantity, remaining_quantity, starts_at, ends_at, created_at FROM sales",
		)

		assertEquals(
			setOf("id", "product_id", "price", "initial_quantity", "remaining_quantity", "starts_at", "ends_at", "created_at"),
			columns.toSet(),
		)
		assertEquals(productId, stored.getValue("product_id"))
		assertEquals(10_000L, stored.getValue("price"))
		assertEquals(100L, stored.getValue("initial_quantity"))
		assertEquals(100L, stored.getValue("remaining_quantity"))
		assertEquals(startsAt, (stored.getValue("starts_at") as Timestamp).toInstant())
		assertEquals(endsAt, (stored.getValue("ends_at") as Timestamp).toInstant())
		assertEquals(createdAt, (stored.getValue("created_at") as Timestamp).toInstant())
	}

	@Test
	@DisplayName("판매 제약_존재하지 않는 상품과 잘못된 판매 값을 거부한다")
	fun 판매_제약_존재하지_않는_상품과_잘못된_판매_값을_거부한다() {
		val startsAt = Instant.parse("2026-09-09T09:00:00Z")
		val endsAt = Instant.parse("2026-09-16T09:00:00Z")
		val createdAt = Instant.parse("2026-09-09T08:00:00Z")

		assertFailsWith<DataIntegrityViolationException> {
			insertSale(Long.MAX_VALUE, 10_000L, 100L, 100L, startsAt, endsAt, createdAt)
		}

		val productId = insertProduct()
		listOf(
			SaleRow(0L, 100L, 100L, startsAt, endsAt),
			SaleRow(10_000L, 0L, 0L, startsAt, endsAt),
			SaleRow(10_000L, 100L, -1L, startsAt, endsAt),
			SaleRow(10_000L, 100L, 101L, startsAt, endsAt),
			SaleRow(10_000L, 100L, 100L, startsAt, startsAt),
		).forEach { row ->
			assertFailsWith<DataIntegrityViolationException> {
				insertSale(
					productId,
					row.price,
					row.initialQuantity,
					row.remainingQuantity,
					row.startsAt,
					row.endsAt,
					createdAt,
				)
			}
		}
	}

	@Test
	@DisplayName("판매 기간_같은 상품의 인접 기간과 다른 상품의 동일 기간을 허용한다")
	fun 판매_기간_같은_상품의_인접_기간과_다른_상품의_동일_기간을_허용한다() {
		val firstProductId = insertProduct()
		val secondProductId = insertProduct()
		val startsAt = Instant.parse("2026-09-09T09:00:00Z")
		val boundary = Instant.parse("2026-09-16T09:00:00Z")
		val endsAt = Instant.parse("2026-09-23T09:00:00Z")
		val createdAt = Instant.parse("2026-09-09T08:00:00Z")

		insertSale(firstProductId, startsAt = startsAt, endsAt = boundary, createdAt = createdAt)
		insertSale(firstProductId, startsAt = boundary, endsAt = endsAt, createdAt = createdAt)
		insertSale(secondProductId, startsAt = startsAt, endsAt = boundary, createdAt = createdAt)

		assertEquals(3, jdbcTemplate.queryForObject("SELECT count(*) FROM sales", Int::class.java))
	}

	@Test
	@DisplayName("판매 기간_같은 상품의 부분 전체 경계 중첩을 거부한다")
	fun 판매_기간_같은_상품의_부분_전체_경계_중첩을_거부한다() {
		val existingStartsAt = Instant.parse("2026-09-09T09:00:00Z")
		val existingEndsAt = Instant.parse("2026-09-16T09:00:00Z")
		val createdAt = Instant.parse("2026-09-09T08:00:00Z")
		val overlappingPeriods = listOf(
			Instant.parse("2026-09-08T09:00:00Z") to Instant.parse("2026-09-10T09:00:00Z"),
			Instant.parse("2026-09-10T09:00:00Z") to Instant.parse("2026-09-15T09:00:00Z"),
			Instant.parse("2026-09-08T09:00:00Z") to Instant.parse("2026-09-17T09:00:00Z"),
			existingStartsAt to existingEndsAt,
		)

		overlappingPeriods.forEach { (startsAt, endsAt) ->
			val productId = insertProduct()
			insertSale(productId, startsAt = existingStartsAt, endsAt = existingEndsAt, createdAt = createdAt)

			assertFailsWith<DataIntegrityViolationException> {
				insertSale(productId, startsAt = startsAt, endsAt = endsAt, createdAt = createdAt)
			}
		}
	}

	@Test
	@DisplayName("판매 기간_동시에 등록한 같은 상품의 중첩 기간 중 하나만 커밋한다")
	fun 판매_기간_동시에_등록한_같은_상품의_중첩_기간_중_하나만_커밋한다() {
		val productId = insertProduct()
		val ready = CountDownLatch(2)
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		val tasks = listOf(
			Instant.parse("2026-09-09T09:00:00Z") to Instant.parse("2026-09-16T09:00:00Z"),
			Instant.parse("2026-09-10T09:00:00Z") to Instant.parse("2026-09-17T09:00:00Z"),
		).map { (startsAt, endsAt) ->
			Callable {
				ready.countDown()
				start.await()
				runCatching {
					transactionTemplate.executeWithoutResult {
						insertSale(
							productId,
							startsAt = startsAt,
							endsAt = endsAt,
							createdAt = Instant.parse("2026-09-09T08:00:00Z"),
						)
					}
				}
			}
		}

		val futures = tasks.map(executor::submit)
		ready.await()
		start.countDown()
		val results = futures.map { it.get() }
		executor.shutdown()

		assertEquals(1, results.count { it.isSuccess })
		assertEquals(1, results.count { it.exceptionOrNull() is DataIntegrityViolationException })
		assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM sales WHERE product_id = ?", Int::class.java, productId))
	}

	private fun insertProduct(): Long = jdbcTemplate.queryForObject(
		"""
		INSERT INTO products (seller_id, name, description, status, created_at)
		VALUES (?, ?, ?, ?, ?) RETURNING id
		""".trimIndent(),
		Long::class.java,
		123L,
		"상품",
		"설명",
		"READY",
		Timestamp.from(Instant.parse("2026-09-09T08:00:00Z")),
	)!!

	private fun insertSale(
		productId: Long,
		price: Long = 10_000L,
		initialQuantity: Long = 100L,
		remainingQuantity: Long = 100L,
		startsAt: Instant,
		endsAt: Instant,
		createdAt: Instant,
	) {
		jdbcTemplate.update(
			"""
			INSERT INTO sales (
				product_id, price, initial_quantity, remaining_quantity, starts_at, ends_at, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			productId,
			price,
			initialQuantity,
			remainingQuantity,
			Timestamp.from(startsAt),
			Timestamp.from(endsAt),
			Timestamp.from(createdAt),
		)
	}

	private data class SaleRow(
		val price: Long,
		val initialQuantity: Long,
		val remainingQuantity: Long,
		val startsAt: Instant,
		val endsAt: Instant,
	)
}
