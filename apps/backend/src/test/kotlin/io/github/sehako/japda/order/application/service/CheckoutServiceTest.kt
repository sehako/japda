package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.domain.repository.CheckoutProductSnapshot
import io.github.sehako.japda.order.domain.repository.CheckoutProductSnapshotQuery
import io.github.sehako.japda.order.domain.repository.CheckoutShippingAddress
import io.github.sehako.japda.order.domain.repository.CheckoutShippingAddressQuery
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@DisplayName("체크아웃 조회 서비스")
class CheckoutServiceTest {
	private val productSnapshotQuery = mock(CheckoutProductSnapshotQuery::class.java)
	private val shippingAddressQuery = mock(CheckoutShippingAddressQuery::class.java)
	private val service = CheckoutService(productSnapshotQuery, shippingAddressQuery)

	@Test
	@DisplayName("서버 단가와 수량으로 예상 총액을 계산하고 구매자 배송지를 조립한다")
	fun 서버_단가와_수량으로_예상_총액과_배송지를_조립한다() {
		`when`(productSnapshotQuery.findBySaleId(100)).thenReturn(snapshot())
		`when`(shippingAddressQuery.findByBuyerId(123)).thenReturn(listOf(address(7), address(9)))

		val response = service.find(123, 100, 2)

		assertEquals(70_000, response.totalPrice)
		assertEquals(35_000, response.unitPrice)
		assertEquals("한정판 상품", response.productName)
		assertEquals("/products/42/image-a", response.representativeImagePath)
		assertEquals(listOf(7L, 9L), response.shippingAddresses.map { it.shippingAddressId })
		assertEquals("집", response.shippingAddresses.first().addressName)
		assertEquals("홍길동", response.shippingAddresses.first().recipientName)
	}

	@Test
	@DisplayName("저장 배송지가 없어도 상품과 빈 배송지 목록을 반환한다")
	fun 저장_배송지_없어도_상품과_빈_목록을_반환한다() {
		`when`(productSnapshotQuery.findBySaleId(100)).thenReturn(snapshot())
		`when`(shippingAddressQuery.findByBuyerId(123)).thenReturn(emptyList())

		val response = service.find(123, 100, 1)

		assertEquals(35_000, response.totalPrice)
		assertTrue(response.shippingAddresses.isEmpty())
	}

	@Test
	@DisplayName("판매 일정이 없으면 판매 일정 미존재 오류를 반환한다")
	fun 판매_일정_없으면_미존재_오류를_반환한다() {
		`when`(productSnapshotQuery.findBySaleId(100)).thenReturn(null)

		assertEquals(OrderErrorCode.SALE_NOT_FOUND, assertFailsWith<OrderException> { service.find(123, 100, 1) }.errorCode)
	}

	@Test
	@DisplayName("예상 총액이 Long 범위를 넘으면 충돌 오류를 반환한다")
	fun 예상_총액_Long_범위_초과_충돌_오류를_반환한다() {
		`when`(productSnapshotQuery.findBySaleId(100)).thenReturn(snapshot(price = Long.MAX_VALUE))

		assertEquals(OrderErrorCode.TOTAL_PRICE_INVALID, assertFailsWith<OrderException> { service.find(123, 100, 2) }.errorCode)
	}

	@Test
	@DisplayName("판매 일정 식별자와 수량은 양수여야 한다")
	fun 판매_일정_식별자와_수량은_양수여야_한다() {
		assertEquals(OrderErrorCode.SALE_ID_INVALID, assertFailsWith<OrderException> { service.find(123, 0, 1) }.errorCode)
		assertEquals(OrderErrorCode.QUANTITY_INVALID, assertFailsWith<OrderException> { service.find(123, 100, 0) }.errorCode)
	}

	@Test
	@DisplayName("대표 이미지가 없으면 내부 데이터 오류로 처리한다")
	fun 대표_이미지_없으면_내부_데이터_오류로_처리한다() {
		`when`(productSnapshotQuery.findBySaleId(100)).thenReturn(snapshot().copy(representativeImageObjectKey = null))

		assertFailsWith<IllegalStateException> { service.find(123, 100, 1) }
	}

	@Test
	@DisplayName("상품이 없으면 판매 일정 미존재로 오인하지 않고 내부 데이터 오류로 처리한다")
	fun 상품_없으면_내부_데이터_오류로_처리한다() {
		`when`(productSnapshotQuery.findBySaleId(100)).thenReturn(snapshot().copy(productName = null))

		assertFailsWith<IllegalStateException> { service.find(123, 100, 1) }
	}

	private fun snapshot(price: Long = 35_000) = CheckoutProductSnapshot(
		saleId = 100,
		productName = "한정판 상품",
		representativeImageObjectKey = "products/42/image-a",
		unitPrice = price,
	)

	private fun address(addressId: Long) = CheckoutShippingAddress(
		shippingAddressId = addressId,
		addressName = "집",
		recipientName = "홍길동",
		phoneNumber = "010-1234-5678",
		postalCode = "06236",
		address = "서울특별시 강남구",
		detailAddress = "101호",
		deliveryMessage = null,
	)
}
