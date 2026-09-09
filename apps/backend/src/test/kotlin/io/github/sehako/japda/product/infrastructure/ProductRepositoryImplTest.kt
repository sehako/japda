package io.github.sehako.japda.product.infrastructure

import io.github.sehako.japda.PostgreSqlTestContainerConfiguration
import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.ProductStatus
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Import(PostgreSqlTestContainerConfiguration::class)
@Transactional
@DisplayName("상품 Repository 구현체")
class ProductRepositoryImplTest(
	@Autowired private val productRepository: ProductRepository,
	@Autowired private val entityManager: EntityManager,
) {

	@Test
	@DisplayName("상품 저장_identity ID와 모든 필드를 PostgreSQL에 저장한다")
	fun 상품_저장_identity_ID와_모든_필드를_PostgreSQL에_저장한다() {
		val createdAt = Instant.parse("2026-09-09T03:00:00Z")
		val first = productRepository.save(Product.create(123L, "상품", "설명", createdAt))
		val second = productRepository.save(Product.create(123L, "상품", "설명", createdAt))

		entityManager.flush()
		entityManager.clear()

		val firstId = assertNotNull(first.id)
		val secondId = assertNotNull(second.id)
		val found = entityManager.find(Product::class.java, firstId)

		assertNotEquals(firstId, secondId)
		assertEquals(123L, found.sellerId)
		assertEquals("상품", found.name)
		assertEquals("설명", found.description)
		assertEquals(ProductStatus.DRAFT, found.status)
		assertEquals(createdAt, found.createdAt)
	}
}
