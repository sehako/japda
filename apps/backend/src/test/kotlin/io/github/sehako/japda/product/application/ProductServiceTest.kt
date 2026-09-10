package io.github.sehako.japda.product.application

import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.ProductStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.jupiter.api.DisplayName

@DisplayName("상품 서비스")
class ProductServiceTest {

	@Test
	@DisplayName("상품 등록 시 현재 시각으로 생성한 상품을 저장하고 응답한다")
	fun 상품_등록_현재_시각으로_생성한_상품을_저장하고_응답한다() {
		val now = Instant.parse("2026-09-10T00:00:00Z")
		val repository = RecordingProductRepository(7L)
		val service = ProductService(repository, Clock.fixed(now, ZoneOffset.UTC))

		val response = service.create(CreateProductDto(1L, "  한정판 상품  ", "  설명  "))

		val savedProduct = repository.savedProduct!!
		assertSame(savedProduct, repository.returnedProduct)
		assertEquals(1L, savedProduct.sellerId)
		assertEquals("한정판 상품", savedProduct.name)
		assertEquals("설명", savedProduct.description)
		assertEquals(ProductStatus.DRAFT, savedProduct.status)
		assertEquals(now, savedProduct.createdAt)
		assertEquals(7L, response.id)
		assertEquals(1L, response.sellerId)
		assertEquals("한정판 상품", response.name)
		assertEquals("설명", response.description)
		assertEquals(ProductStatus.DRAFT, response.status)
		assertEquals(now, response.createdAt)
	}

	private class RecordingProductRepository(
		private val generatedId: Long,
	) : ProductRepository {
		var savedProduct: Product? = null
		var returnedProduct: Product? = null

		override fun save(product: Product): Product {
			savedProduct = product
			Product::class.java.getDeclaredField("id").apply {
				isAccessible = true
				set(product, generatedId)
			}
			return product.also { returnedProduct = it }
		}
	}
}
