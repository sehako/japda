package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.domain.repository.CheckoutProductSnapshot
import io.github.sehako.japda.order.domain.repository.CheckoutProductSnapshotQuery
import io.github.sehako.japda.order.domain.repository.CheckoutShippingAddress
import io.github.sehako.japda.order.domain.repository.CheckoutShippingAddressQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName

@DisplayName("체크아웃 조회 서비스의 분리된 조회 계약 조립")
class CheckoutServiceQueryCompositionTest {
	@Test
	@DisplayName("상품 스냅샷과 구매자 배송지를 조립해 예상 총액을 반환한다")
	fun 상품_스냅샷과_구매자_배송지를_조립해_예상_총액을_반환한다() {
		val productSnapshotQuery = object : CheckoutProductSnapshotQuery {
			override fun findBySaleId(saleId: Long) = CheckoutProductSnapshot(
				saleId = saleId,
				productName = "한정판 상품",
				representativeImageObjectKey = "products/42/image-a",
				unitPrice = 35_000,
			)
		}
		val shippingAddressQuery = object : CheckoutShippingAddressQuery {
			override fun findByBuyerId(buyerId: Long) = listOf(
				CheckoutShippingAddress(
					shippingAddressId = 7,
					addressName = "집",
					recipientName = "홍길동",
					phoneNumber = "010-1234-5678",
					postalCode = "06236",
					address = "서울특별시 강남구",
					detailAddress = "101호",
					deliveryMessage = "문 앞에 놓아주세요",
				),
			)
		}
		val service = CheckoutService(productSnapshotQuery, shippingAddressQuery)

		val response = service.find(buyerId = 123, saleId = 100, quantity = 2)

		assertEquals(100L, response.saleId)
		assertEquals("한정판 상품", response.productName)
		assertEquals("/products/42/image-a", response.representativeImagePath)
		assertEquals(35_000L, response.unitPrice)
		assertEquals(70_000L, response.totalPrice)
		assertEquals(7L, response.shippingAddresses.single().shippingAddressId)
		assertEquals("홍길동", response.shippingAddresses.single().recipientName)
	}
}
