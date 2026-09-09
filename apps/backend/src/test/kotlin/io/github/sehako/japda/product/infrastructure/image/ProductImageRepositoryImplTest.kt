package io.github.sehako.japda.product.infrastructure.image

import io.github.sehako.japda.PostgreSqlTestContainerConfiguration
import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.image.ProductImage
import io.github.sehako.japda.product.domain.image.ProductImageRepository
import io.github.sehako.japda.product.domain.ProductRepository
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Import(PostgreSqlTestContainerConfiguration::class)
@Transactional
@DisplayName("상품 이미지 Repository 구현체")
class ProductImageRepositoryImplTest(
	@Autowired private val productRepository: ProductRepository,
	@Autowired private val productImageRepository: ProductImageRepository,
	@Autowired private val entityManager: EntityManager,
) {

	@Test
	@DisplayName("상품 이미지 저장_요청 순서와 대표 여부를 PostgreSQL에 보존한다")
	fun 상품_이미지_저장_요청_순서와_대표_여부를_PostgreSQL에_보존한다() {
		val product = saveProduct()
		val images = listOf(
			image(product, "products/1/first.jpg", "image/jpeg", 0, true),
			image(product, "products/1/second.webp", "image/webp", 1, false),
		)

		val saved = productImageRepository.saveAll(images)
		entityManager.flush()
		entityManager.clear()
		val productId = assertNotNull(product.id)
		val found = productImageRepository.findAllByProductIdOrderBySortOrder(productId)

		assertEquals(2, saved.size)
		assertEquals(listOf(0, 1), found.map { it.sortOrder })
		assertEquals(listOf(true, false), found.map { it.representative })
		assertEquals(listOf("image/jpeg", "image/webp"), found.map { it.contentType })
		assertEquals(true, productImageRepository.existsByProductId(productId))
	}

	@Test
	@DisplayName("상품 이미지 저장_상품별 중복 정렬 순서를 DB 제약으로 거부한다")
	fun 상품_이미지_저장_상품별_중복_정렬_순서를_DB_제약으로_거부한다() {
		val product = saveProduct()

		assertConstraintViolation(
			image(product, "products/1/first.jpg", sortOrder = 0, representative = true),
			image(product, "products/1/second.jpg", sortOrder = 0, representative = false),
		)
	}

	@Test
	@DisplayName("상품 이미지 저장_상품별 대표 이미지 두 장을 DB 제약으로 거부한다")
	fun 상품_이미지_저장_상품별_대표_이미지_두_장을_DB_제약으로_거부한다() {
		val product = saveProduct()

		assertConstraintViolation(
			image(product, "products/1/first.jpg", sortOrder = 0, representative = true),
			image(product, "products/1/second.jpg", sortOrder = 1, representative = true),
		)
	}

	@Test
	@DisplayName("상품 이미지 저장_중복 객체 키를 DB 제약으로 거부한다")
	fun 상품_이미지_저장_중복_객체_키를_DB_제약으로_거부한다() {
		val firstProduct = saveProduct()
		val secondProduct = saveProduct()

		assertConstraintViolation(
			image(firstProduct, "products/shared/image.jpg", sortOrder = 0, representative = true),
			image(secondProduct, "products/shared/image.jpg", sortOrder = 0, representative = true),
		)
	}

	@Test
	@DisplayName("상품 이미지 저장_범위 밖 크기와 정렬 순서를 DB 제약으로 거부한다")
	fun 상품_이미지_저장_범위_밖_크기와_정렬_순서를_DB_제약으로_거부한다() {
		assertInvalidRow(sizeBytes = 0L)
	}

	@Test
	@DisplayName("상품 이미지 저장_10MiB를 초과하는 크기를 DB 제약으로 거부한다")
	fun 상품_이미지_저장_10MiB를_초과하는_크기를_DB_제약으로_거부한다() {
		assertInvalidRow(sizeBytes = ProductImage.MAX_SIZE_BYTES + 1)
	}

	@Test
	@DisplayName("상품 이미지 저장_0보다 작은 정렬 순서를 DB 제약으로 거부한다")
	fun 상품_이미지_저장_0보다_작은_정렬_순서를_DB_제약으로_거부한다() {
		assertInvalidRow(sortOrder = -1)
	}

	@Test
	@DisplayName("상품 이미지 저장_9보다 큰 정렬 순서를 DB 제약으로 거부한다")
	fun 상품_이미지_저장_9보다_큰_정렬_순서를_DB_제약으로_거부한다() {
		assertInvalidRow(sortOrder = 10)
	}

	@Test
	@DisplayName("상품 이미지 저장_허용하지 않는 MIME type을 DB 제약으로 거부한다")
	fun 상품_이미지_저장_허용하지_않는_MIME_type을_DB_제약으로_거부한다() {
		assertInvalidRow(contentType = "image/gif")
	}

	private fun assertInvalidRow(
		sizeBytes: Long = 1L,
		contentType: String = "image/jpeg",
		sortOrder: Int = 0,
	) {
		val product = saveProduct()
		val invalid = ProductImage(
			product = product,
			objectKey = "products/${product.id}/invalid.jpg",
			contentType = contentType,
			sizeBytes = sizeBytes,
			sortOrder = sortOrder,
			representative = true,
			createdAt = Instant.parse("2026-09-09T04:00:00Z"),
		)

		assertConstraintViolation(invalid)
	}

	private fun assertConstraintViolation(vararg images: ProductImage) {
		assertFailsWith<DataIntegrityViolationException> {
			productImageRepository.saveAll(images.toList())
			entityManager.flush()
		}
	}

	private fun saveProduct(): Product = productRepository.save(
		Product.create(123L, "상품", "설명", Instant.parse("2026-09-09T03:00:00Z")),
	)

	private fun image(
		product: Product,
		objectKey: String,
		contentType: String = "image/jpeg",
		sortOrder: Int,
		representative: Boolean,
	): ProductImage = ProductImage.create(
		product = product,
		objectKey = objectKey,
		contentType = contentType,
		sizeBytes = 1_024L,
		sortOrder = sortOrder,
		representative = representative,
		createdAt = Instant.parse("2026-09-09T04:00:00Z"),
	)
}
