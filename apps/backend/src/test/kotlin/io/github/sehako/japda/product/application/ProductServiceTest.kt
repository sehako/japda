package io.github.sehako.japda.product.application

import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.ProductStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("상품 서비스")
class ProductServiceTest {

	@Test
	@DisplayName("상품 등록_현재 시각으로 생성하고 저장된 상품 응답을 반환한다")
	fun 상품_등록_현재_시각으로_생성하고_저장된_상품_응답을_반환한다() {
		val now = Instant.parse("2026-09-09T03:00:00Z")
		val repository = RecordingProductRepository(savedId = 41L)
		val service = ProductService(repository, Clock.fixed(now, ZoneOffset.UTC))

		val response = service.create(
			CreateProductDto(
				sellerId = 123L,
				name = " 상품 ",
				description = " 설명 ",
			),
		)

		val savedProduct = assertNotNull(repository.receivedProduct)
		assertEquals(123L, savedProduct.sellerId)
		assertEquals("상품", savedProduct.name)
		assertEquals("설명", savedProduct.description)
		assertEquals(ProductStatus.DRAFT, savedProduct.status)
		assertEquals(now, savedProduct.createdAt)
		assertEquals(
			ProductResponse(41L, 123L, "상품", "설명", ProductStatus.DRAFT, now),
			response,
		)
	}

	private class RecordingProductRepository(
		private val savedId: Long,
	) : ProductRepository {
		var receivedProduct: Product? = null
			private set

		override fun save(product: Product): Product {
			receivedProduct = product
			return Product(
				id = savedId,
				sellerId = product.sellerId,
				name = product.name,
				description = product.description,
				status = product.status,
				createdAt = product.createdAt,
			)
		}
	}
}
