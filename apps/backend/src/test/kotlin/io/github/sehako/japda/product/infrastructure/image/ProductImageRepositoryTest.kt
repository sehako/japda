package io.github.sehako.japda.product.infrastructure.image

import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.image.ProductImage
import io.github.sehako.japda.product.domain.image.ProductImageRepository
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProductImageRepositoryImpl::class, io.github.sehako.japda.product.infrastructure.ProductRepositoryImpl::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("상품 이미지 영속성")
class ProductImageRepositoryTest {
	@Autowired
	private lateinit var productRepository: ProductRepository

	@Autowired
	private lateinit var productImageRepository: ProductImageRepository

	@Autowired
	private lateinit var entityManager: EntityManager

	@Test
	@DisplayName("이미지 묶음을 저장하고 객체 키로 참조된 이미지를 찾는다")
	fun 이미지_묶음_저장_객체_키로_참조된_이미지를_찾는다() {
		val productId = assertNotNull(productRepository.save(Product.create(1L, "상품", null, Instant.parse("2026-09-10T00:00:00Z"))).id)
		val images = listOf(
			ProductImage.create(productId, "products/$productId/request/first.jpg", "image/jpeg", 3, 0, true, Instant.parse("2026-09-10T00:00:01Z")),
			ProductImage.create(productId, "products/$productId/request/second.png", "image/png", 8, 1, false, Instant.parse("2026-09-10T00:00:02Z")),
		)

		val saved = productImageRepository.saveAll(images)
		entityManager.flush()
		entityManager.clear()
		val found = productImageRepository.findAllByObjectKeyIn(listOf(images[1].objectKey, "unreferenced"))

		assertEquals(2, saved.size)
		assertEquals(listOf(images[1].objectKey), found.map { it.objectKey })
		assertNotNull(found.single().id)
	}

	companion object {
		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
