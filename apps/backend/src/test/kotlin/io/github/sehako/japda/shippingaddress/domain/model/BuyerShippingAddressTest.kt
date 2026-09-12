package io.github.sehako.japda.shippingaddress.domain.model

import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressErrorCode
import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName

@DisplayName("구매자 배송지")
class BuyerShippingAddressTest {
	@Test
	@DisplayName("배송 정보의 앞뒤 공백을 제거하고 빈 배송 메시지를 null로 변환한다")
	fun 배송_정보_정규화_빈_배송_메시지를_null로_변환한다() {
		val address = BuyerShippingAddress.create(
			buyerShippingAddressBookId = 7L,
			addressName = " 집 ",
			recipientName = " 홍길동 ",
			phoneNumber = " 010-1234-5678 ",
			postalCode = " 06236 ",
			address = " 서울시 강남구 ",
			detailAddress = " 101호 ",
			deliveryMessage = "   ",
			createdAt = NOW,
		)

		assertEquals(7L, address.buyerShippingAddressBookId)
		assertEquals("집", address.addressName)
		assertEquals("홍길동", address.recipientName)
		assertEquals("010-1234-5678", address.phoneNumber)
		assertEquals("06236", address.postalCode)
		assertEquals("서울시 강남구", address.address)
		assertEquals("101호", address.detailAddress)
		assertNull(address.deliveryMessage)
		assertEquals(NOW, address.createdAt)
	}

	@Test
	@DisplayName("필수 필드가 비었거나 최대 길이를 넘으면 필드별 오류를 반환한다")
	fun 필수_필드_오류_필드별_오류를_반환한다() {
		val cases = listOf(
			BuyerShippingAddressErrorCode.NAME_INVALID to { create(addressName = " ") },
			BuyerShippingAddressErrorCode.RECIPIENT_NAME_INVALID to { create(recipientName = "가".repeat(101)) },
			BuyerShippingAddressErrorCode.PHONE_NUMBER_INVALID to { create(phoneNumber = "1".repeat(31)) },
			BuyerShippingAddressErrorCode.POSTAL_CODE_INVALID to { create(postalCode = "1".repeat(21)) },
			BuyerShippingAddressErrorCode.ADDRESS_INVALID to { create(address = "가".repeat(256)) },
			BuyerShippingAddressErrorCode.DETAIL_ADDRESS_INVALID to { create(detailAddress = "가".repeat(256)) },
			BuyerShippingAddressErrorCode.DELIVERY_MESSAGE_INVALID to { create(deliveryMessage = "가".repeat(501)) },
		)

		cases.forEach { (expected, block) ->
			assertEquals(expected, assertFailsWith<BuyerShippingAddressException> { block() }.errorCode)
		}
	}

	private fun create(
		addressName: String? = "집",
		recipientName: String? = "홍길동",
		phoneNumber: String? = "010",
		postalCode: String? = "06236",
		address: String? = "서울",
		detailAddress: String? = "101호",
		deliveryMessage: String? = null,
	): BuyerShippingAddress = BuyerShippingAddress.create(
		1L,
		addressName,
		recipientName,
		phoneNumber,
		postalCode,
		address,
		detailAddress,
		deliveryMessage,
		NOW,
	)

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-12T03:34:56Z")
	}
}
