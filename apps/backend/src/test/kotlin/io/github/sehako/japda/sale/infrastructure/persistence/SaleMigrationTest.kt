package io.github.sehako.japda.sale.infrastructure.persistence

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("판매 migration")
class SaleMigrationTest {
	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	@DisplayName("유효한 판매일과 판매 일정을 저장한다")
	fun 유효한_판매일과_판매_일정을_저장한다() {
		val productId = insertProduct()
		insertSaleDay(capacity = 20, registeredCount = 1)

		jdbcTemplate.update(
			"""INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at)
				VALUES (?, 1, ?, 35000, 100, now())""",
			productId,
			SALE_DATE,
		)

		assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM sales", Int::class.java))
	}

	@Test
	@DisplayName("판매일 정원이 양수가 아니면 저장을 거부한다")
	fun 판매일_정원이_양수가_아니면_저장을_거부한다() {
		assertFailsWith<DataIntegrityViolationException> {
			insertSaleDay(capacity = 0, registeredCount = 0)
		}
	}

	@Test
	@DisplayName("판매일 등록 수가 정원을 초과하면 저장을 거부한다")
	fun 판매일_등록_수_정원_초과_저장을_거부한다() {
		assertFailsWith<DataIntegrityViolationException> {
			insertSaleDay(capacity = 20, registeredCount = 21)
		}
	}

	@Test
	@DisplayName("같은 판매자의 같은 판매일 일정이 중복되면 저장을 거부한다")
	fun 같은_판매자_같은_판매일_일정_중복_저장을_거부한다() {
		val firstProductId = insertProduct()
		val secondProductId = insertProduct()
		insertSaleDay(capacity = 20, registeredCount = 2)
		insertSale(firstProductId, sellerId = 1)

		assertFailsWith<DataIntegrityViolationException> {
			insertSale(secondProductId, sellerId = 1)
		}
	}

	@Test
	@DisplayName("존재하지 않는 상품의 판매 일정 저장을 거부한다")
	fun 존재하지_않는_상품의_판매_일정_저장을_거부한다() {
		insertSaleDay(capacity = 20, registeredCount = 1)

		assertFailsWith<DataIntegrityViolationException> {
			insertSale(Long.MAX_VALUE, sellerId = 1)
		}
	}

	@Test
	@DisplayName("판매자가 양수가 아니면 저장을 거부한다")
	fun 판매자가_양수가_아니면_저장을_거부한다() {
		val productId = insertProduct()
		insertSaleDay(capacity = 20, registeredCount = 1)

		assertFailsWith<DataIntegrityViolationException> { insertSale(productId, sellerId = 0) }
	}

	@Test
	@DisplayName("가격이 양수가 아니면 저장을 거부한다")
	fun 가격이_양수가_아니면_저장을_거부한다() {
		val productId = insertProduct()
		insertSaleDay(capacity = 20, registeredCount = 1)

		assertFailsWith<DataIntegrityViolationException> { insertSale(productId, sellerId = 1, price = 0) }
	}

	@Test
	@DisplayName("수량이 양수가 아니면 저장을 거부한다")
	fun 수량이_양수가_아니면_저장을_거부한다() {
		val productId = insertProduct()
		insertSaleDay(capacity = 20, registeredCount = 1)

		assertFailsWith<DataIntegrityViolationException> { insertSale(productId, sellerId = 1, quantity = 0) }
	}

	private fun insertProduct(): Long = jdbcTemplate.queryForObject(
		"INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '상품', 'READY', now()) RETURNING id",
		Long::class.java,
	)!!

	private fun insertSaleDay(capacity: Int, registeredCount: Int) {
		jdbcTemplate.update(
			"INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, ?, ?)",
			SALE_DATE,
			capacity,
			registeredCount,
		)
	}

	private fun insertSale(
		productId: Long,
		sellerId: Long,
		price: Long = 35_000,
		quantity: Int = 100,
	) {
		jdbcTemplate.update(
			"""INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at)
				VALUES (?, ?, ?, ?, ?, now())""",
			productId,
			sellerId,
			SALE_DATE,
			price,
			quantity,
		)
	}

	companion object {
		private val SALE_DATE = LocalDate.of(2026, 9, 12)

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
