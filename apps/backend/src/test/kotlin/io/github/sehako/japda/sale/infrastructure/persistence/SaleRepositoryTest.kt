package io.github.sehako.japda.sale.infrastructure.persistence

import io.github.sehako.japda.sale.domain.model.Sale
import io.github.sehako.japda.sale.domain.repository.SaleDayRepository
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import io.github.sehako.japda.sale.exception.SaleSellerAlreadyRegisteredPersistenceException
import jakarta.persistence.EntityManager
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SaleRepositoryImpl::class, SaleDayRepositoryImpl::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("판매 일정 영속성")
class SaleRepositoryTest {
	@Autowired
	private lateinit var saleRepository: SaleRepository

	@Autowired
	private lateinit var saleDayRepository: SaleDayRepository

	@Autowired
	private lateinit var entityManager: EntityManager

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Autowired
	private lateinit var transactionTemplate: TransactionTemplate

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM sale_days")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
	}

	@Test
	@DisplayName("판매 일정을 저장하고 판매자와 판매일의 등록 여부를 조회한다")
	fun 판매_일정_저장_판매자와_판매일_등록_여부를_조회한다() {
		val productId = insertProduct()
		saleDayRepository.createIfAbsent(SALE_DATE, 20)
		val sale = Sale.create(productId, 1L, SALE_DATE, 35_000L, 100, CREATED_AT)

		assertFalse(saleRepository.existsBySellerIdAndSaleDate(1L, SALE_DATE))
		val savedSale = saleRepository.save(sale)
		entityManager.flush()
		entityManager.clear()

		val savedSaleId = assertNotNull(savedSale.id)
		assertTrue(saleRepository.existsBySellerIdAndSaleDate(1L, SALE_DATE))
		val row = jdbcTemplate.queryForMap(
			"SELECT product_id, seller_id, sale_date, price, quantity, created_at FROM sales WHERE id = ?",
			savedSaleId,
		)
		assertEquals(productId, (row["product_id"] as Number).toLong())
		assertEquals(1L, (row["seller_id"] as Number).toLong())
		assertEquals(SALE_DATE, (row["sale_date"] as java.sql.Date).toLocalDate())
		assertEquals(35_000L, (row["price"] as Number).toLong())
		assertEquals(100, (row["quantity"] as Number).toInt())
		assertEquals(CREATED_AT, (row["created_at"] as java.sql.Timestamp).toInstant())
	}

	@Test
	@DisplayName("판매 일정의 수량만 잠금 없이 조회한다")
	fun 판매_일정_수량만_잠금_없이_조회한다() {
		val productId = insertProduct()
		saleDayRepository.createIfAbsent(SALE_DATE, 20)
		val saleId = assertNotNull(
			saleRepository.save(Sale.create(productId, 1L, SALE_DATE, 35_000L, 37, CREATED_AT)).id,
		)
		entityManager.flush()
		entityManager.clear()

		assertEquals(37, saleRepository.findQuantityById(saleId))
		assertEquals(null, saleRepository.findQuantityById(Long.MAX_VALUE))
	}

	@Test
	@DisplayName("판매자와 판매일 유일성 충돌만 판매자 중복 persistence 오류로 분류한다")
	fun 판매자와_판매일_유일성_충돌만_판매자_중복_persistence_오류로_분류한다() {
		val firstProductId = insertProduct()
		val secondProductId = insertProduct()
		saleDayRepository.createIfAbsent(SALE_DATE, 20)
		saleRepository.save(Sale.create(firstProductId, 1L, SALE_DATE, 35_000L, 100, CREATED_AT))

		assertFailsWith<SaleSellerAlreadyRegisteredPersistenceException> {
			saleRepository.save(Sale.create(secondProductId, 1L, SALE_DATE, 40_000L, 50, CREATED_AT))
		}
	}

	@Test
	@DisplayName("다른 무결성 오류는 판매자 중복 persistence 오류로 분류하지 않는다")
	fun 다른_무결성_오류_판매자_중복_persistence_오류로_분류하지_않는다() {
		saleDayRepository.createIfAbsent(SALE_DATE, 20)

		assertFailsWith<DataIntegrityViolationException> {
			saleRepository.save(Sale.create(Long.MAX_VALUE, 1L, SALE_DATE, 35_000L, 100, CREATED_AT))
		}
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("자리 증가 뒤 판매 일정 저장이 실패하면 판매일 등록 수를 원복한다")
	fun 자리_증가_뒤_판매_일정_저장_실패_판매일_등록_수를_원복한다() {
		transactionTemplate.execute {
			val productId = insertProduct()
			saleDayRepository.createIfAbsent(SALE_DATE, 20)
			val saleDay = assertNotNull(saleDayRepository.findBySaleDateForUpdate(SALE_DATE))
			saleDay.reserve()
			saleDayRepository.save(saleDay)
			saleRepository.save(Sale.create(productId, 1L, SALE_DATE, 35_000L, 100, CREATED_AT))
		}
		val secondProductId = assertNotNull(transactionTemplate.execute { insertProduct() })

		assertFailsWith<SaleSellerAlreadyRegisteredPersistenceException> {
			transactionTemplate.execute {
				val saleDay = assertNotNull(saleDayRepository.findBySaleDateForUpdate(SALE_DATE))
				saleDay.reserve()
				saleDayRepository.save(saleDay)
				saleRepository.save(Sale.create(secondProductId, 1L, SALE_DATE, 40_000L, 50, CREATED_AT))
			}
		}

		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"SELECT registered_count FROM sale_days WHERE sale_date = ?",
				Int::class.java,
				SALE_DATE,
			),
		)
		assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM sales", Int::class.java))
	}

	private fun insertProduct(): Long = jdbcTemplate.queryForObject(
		"INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '상품', 'READY', now()) RETURNING id",
		Long::class.java,
	)!!

	companion object {
		private val SALE_DATE = LocalDate.of(2026, 9, 12)
		private val CREATED_AT = Instant.parse("2026-09-11T00:00:01Z")

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
