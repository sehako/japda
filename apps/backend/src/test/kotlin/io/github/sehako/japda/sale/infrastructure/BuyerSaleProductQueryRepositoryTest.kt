package io.github.sehako.japda.sale.infrastructure

import io.github.sehako.japda.sale.domain.BuyerSaleProductQueryRepository
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.hibernate.SessionFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(BuyerSaleProductQueryRepositoryImpl::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("구매자 판매 상품 조회 영속성")
class BuyerSaleProductQueryRepositoryTest {
	@Autowired
	private lateinit var repository: BuyerSaleProductQueryRepository

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Autowired
	private lateinit var sessionFactory: SessionFactory

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM sale_days")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
	}

	@Test
	@DisplayName("판매 일정과 상품 및 대표 이미지를 조회 결과로 매핑한다")
	fun 판매_일정과_상품_및_대표_이미지_조회_결과로_매핑한다() {
		val productId = insertProduct("한정판 상품", null)
		insertImage(productId, "products/$productId/request/representative", true, 0)
		val saleId = insertSale(productId, 1L, SALE_DATE, 35_000L, 100, CREATED_AT)

		val result = repository.findAllBySaleDate(SALE_DATE).single()

		assertEquals(saleId, result.saleId)
		assertEquals(productId, result.productId)
		assertEquals("한정판 상품", result.name)
		assertNull(result.description)
		assertEquals(35_000L, result.price)
		assertEquals(100, result.quantity)
		assertEquals(SALE_DATE, result.saleDate)
		assertEquals(CREATED_AT, result.createdAt)
		assertEquals("products/$productId/request/representative", result.representativeImageObjectKey)
	}

	@Test
	@DisplayName("같은 판매일은 생성 시각과 판매 ID 순으로 일정마다 한 번씩 조회한다")
	fun 같은_판매일_생성_시각과_판매_ID_순으로_일정마다_한번씩_조회한다() {
		val firstProductId = insertProduct("첫 상품", "첫 설명")
		val secondProductId = insertProduct("둘째 상품", "둘째 설명")
		val otherDateProductId = insertProduct("다른 날짜 상품", null)
		insertImage(firstProductId, "products/$firstProductId/request/representative", true, 0)
		insertImage(firstProductId, "products/$firstProductId/request/ordinary", false, 1)
		insertImage(secondProductId, "products/$secondProductId/request/representative", true, 0)
		insertImage(otherDateProductId, "products/$otherDateProductId/request/representative", true, 0)
		val laterId = insertSale(firstProductId, 1L, SALE_DATE, 20_000L, 10, CREATED_AT.plusSeconds(1))
		val sameTimeFirstId = insertSale(secondProductId, 2L, SALE_DATE, 30_000L, 20, CREATED_AT)
		insertSale(otherDateProductId, 3L, SALE_DATE.plusDays(1), 40_000L, 30, CREATED_AT.minusSeconds(1))

		val results = repository.findAllBySaleDate(SALE_DATE)

		assertEquals(listOf(sameTimeFirstId, laterId), results.map { it.saleId })
		assertEquals(listOf("둘째 상품", "첫 상품"), results.map { it.name })
	}

	@Test
	@DisplayName("판매 상품 목록을 한 번의 join 쿼리로 조회한다")
	fun 판매_상품_목록_한번의_join_쿼리로_조회한다() {
		val productId = insertProduct("상품", "설명")
		insertImage(productId, "products/$productId/request/representative", true, 0)
		insertSale(productId, 1L, SALE_DATE, 35_000L, 100, CREATED_AT)
		val statistics = sessionFactory.statistics
		statistics.clear()

		val results = repository.findAllBySaleDate(SALE_DATE)

		assertEquals(1, results.size)
		assertEquals(1L, statistics.prepareStatementCount)
	}

	@Test
	@DisplayName("판매 상품 상세는 모든 이미지를 표시 순서와 대표 여부대로 조립한다")
	fun 판매_상품_상세_모든_이미지를_표시_순서와_대표_여부대로_조립한다() {
		val productId = insertProduct("상세 상품", null)
		val otherProductId = insertProduct("다른 상품", "다른 설명")
		insertImage(productId, "products/$productId/request/second", false, 1)
		insertImage(otherProductId, "products/$otherProductId/request/other", true, 0)
		insertImage(productId, "products/$productId/request/first", true, 0)
		val saleId = insertSale(productId, 1L, SALE_DATE, 35_000L, 100, CREATED_AT)
		insertSale(otherProductId, 2L, SALE_DATE, 20_000L, 10, CREATED_AT)

		val result = repository.findDetailBySaleId(saleId)!!

		assertEquals(saleId, result.saleId)
		assertEquals(productId, result.productId)
		assertEquals("상세 상품", result.name)
		assertNull(result.description)
		assertEquals(35_000L, result.price)
		assertEquals(100, result.quantity)
		assertEquals(SALE_DATE, result.saleDate)
		assertEquals(listOf(0, 1), result.images.map { it.displayOrder })
		assertEquals(listOf(true, false), result.images.map { it.isRepresentative })
		assertEquals(
			listOf("products/$productId/request/first", "products/$productId/request/second"),
			result.images.map { it.objectKey },
		)
	}

	@Test
	@DisplayName("이미지가 한 장과 열 장인 판매 상품 상세를 모두 조회한다")
	fun 이미지가_한장과_열장인_판매_상품_상세를_모두_조회한다() {
		val oneImageProductId = insertProduct("한 장 상품", "설명")
		val tenImageProductId = insertProduct("열 장 상품", "설명")
		insertImage(oneImageProductId, "products/$oneImageProductId/request/0", true, 0)
		repeat(10) { displayOrder ->
			insertImage(
				tenImageProductId,
				"products/$tenImageProductId/request/$displayOrder",
				displayOrder == 5,
				displayOrder,
			)
		}
		val oneImageSaleId = insertSale(oneImageProductId, 1L, SALE_DATE, 10_000L, 1, CREATED_AT)
		val tenImageSaleId = insertSale(tenImageProductId, 2L, SALE_DATE, 20_000L, 10, CREATED_AT)

		assertEquals(1, repository.findDetailBySaleId(oneImageSaleId)!!.images.size)
		assertEquals((0..9).toList(), repository.findDetailBySaleId(tenImageSaleId)!!.images.map { it.displayOrder })
	}

	@Test
	@DisplayName("존재하지 않는 판매 일정 상세는 null을 반환한다")
	fun 존재하지_않는_판매_일정_상세_null을_반환한다() {
		assertNull(repository.findDetailBySaleId(Long.MAX_VALUE))
	}

	@Test
	@DisplayName("판매 상품 상세를 한 번의 join 쿼리로 조회한다")
	fun 판매_상품_상세_한번의_join_쿼리로_조회한다() {
		val productId = insertProduct("상품", "설명")
		insertImage(productId, "products/$productId/request/representative", true, 0)
		val saleId = insertSale(productId, 1L, SALE_DATE, 35_000L, 100, CREATED_AT)
		val statistics = sessionFactory.statistics
		statistics.clear()

		val result = repository.findDetailBySaleId(saleId)

		assertEquals(saleId, result?.saleId)
		assertEquals(1L, statistics.prepareStatementCount)
	}

	private fun insertProduct(name: String, description: String?): Long = jdbcTemplate.queryForObject(
		"INSERT INTO products (seller_id, name, description, status, created_at) VALUES (?, ?, ?, 'READY', ?) RETURNING id",
		Long::class.java,
		100L + productSequence++,
		name,
		description,
		Timestamp.from(CREATED_AT),
	)!!

	private fun insertImage(productId: Long, objectKey: String, representative: Boolean, displayOrder: Int) {
		jdbcTemplate.update(
			"INSERT INTO product_images (product_id, object_key, content_type, size_bytes, display_order, is_representative, created_at) VALUES (?, ?, 'image/jpeg', 100, ?, ?, ?)",
			productId,
			objectKey,
			displayOrder,
			representative,
			Timestamp.from(CREATED_AT),
		)
	}

	private fun insertSale(
		productId: Long,
		sellerId: Long,
		saleDate: LocalDate,
		price: Long,
		quantity: Int,
		createdAt: Instant,
	): Long {
		jdbcTemplate.update(
			"INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, 20, 0) ON CONFLICT DO NOTHING",
			saleDate,
		)
		return jdbcTemplate.queryForObject(
			"INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
			Long::class.java,
			productId,
			sellerId,
			saleDate,
			price,
			quantity,
			Timestamp.from(createdAt),
		)!!
	}

	companion object {
		private val SALE_DATE = LocalDate.of(2026, 9, 10)
		private val CREATED_AT = Instant.parse("2026-09-09T00:00:01Z")
		private var productSequence = 1L

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
