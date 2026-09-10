package io.github.sehako.japda.product.infrastructure.image

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
@DisplayName("상품 이미지 migration")
class ProductImageMigrationTest {
	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	@DisplayName("READY 상품과 유효한 이미지 메타데이터를 저장한다")
	fun READY_상품과_유효한_이미지_메타데이터를_저장한다() {
		val productId = insertProduct("READY")

		insertImage(productId, "products/$productId/request/image.png", "image/png", 10_485_760, 0, true)

		assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM product_images WHERE product_id = ?", Int::class.java, productId))
	}

	@Test
	@DisplayName("같은 상품의 표시 순서가 중복되면 저장을 거부한다")
	fun 같은_상품의_표시_순서_중복_저장을_거부한다() {
		val productId = insertProduct("DRAFT")
		insertImage(productId, "products/$productId/request/first.jpg", "image/jpeg", 1, 0, true)

		assertFailsWith<DataIntegrityViolationException> {
			insertImage(productId, "products/$productId/request/second.jpg", "image/jpeg", 1, 0, false)
		}
	}

	@Test
	@DisplayName("같은 상품의 대표 이미지가 둘이면 저장을 거부한다")
	fun 같은_상품의_대표_이미지_둘_저장을_거부한다() {
		val productId = insertProduct("DRAFT")
		insertImage(productId, "products/$productId/request/first.webp", "image/webp", 12, 0, true)

		assertFailsWith<DataIntegrityViolationException> {
			insertImage(productId, "products/$productId/request/second.webp", "image/webp", 12, 1, true)
		}
	}

	@Test
	@DisplayName("존재하지 않는 상품의 이미지 저장을 거부한다")
	fun 존재하지_않는_상품의_이미지_저장을_거부한다() {
		assertFailsWith<DataIntegrityViolationException> {
			insertImage(Long.MAX_VALUE, "products/missing/request/image.png", "image/png", 1, 0, true)
		}
	}

	private fun insertProduct(status: String): Long = jdbcTemplate.queryForObject(
		"INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '상품', ?, now()) RETURNING id",
		Long::class.java,
		status,
	)!!

	private fun insertImage(
		productId: Long,
		objectKey: String,
		contentType: String,
		sizeBytes: Long,
		displayOrder: Int,
		isRepresentative: Boolean,
	) {
		jdbcTemplate.update(
			"""INSERT INTO product_images
				(product_id, object_key, content_type, size_bytes, display_order, is_representative, created_at)
				VALUES (?, ?, ?, ?, ?, ?, now())""",
			productId,
			objectKey,
			contentType,
			sizeBytes,
			displayOrder,
			isRepresentative,
		)
	}

	companion object {
		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
