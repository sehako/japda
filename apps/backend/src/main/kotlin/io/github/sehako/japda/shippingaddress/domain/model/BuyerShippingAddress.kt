package io.github.sehako.japda.shippingaddress.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
	name = "buyer_shipping_addresses",
	uniqueConstraints = [
		UniqueConstraint(
			name = "buyer_shipping_addresses_book_id_address_name_unique",
			columnNames = ["buyer_shipping_address_book_id", "address_name"],
		),
	],
)
class BuyerShippingAddress private constructor(
	id: Long?,
	@field:Column(name = "buyer_shipping_address_book_id", nullable = false)
	val buyerShippingAddressBookId: Long,
	@field:Column(name = "address_name", nullable = false, length = 100)
	val addressName: String,
	@field:Column(name = "recipient_name", nullable = false, length = 100)
	val recipientName: String,
	@field:Column(name = "phone_number", nullable = false, length = 30)
	val phoneNumber: String,
	@field:Column(name = "postal_code", nullable = false, length = 20)
	val postalCode: String,
	@field:Column(nullable = false, length = 255)
	val address: String,
	@field:Column(name = "detail_address", nullable = false, length = 255)
	val detailAddress: String,
	@field:Column(name = "delivery_message", length = 500)
	val deliveryMessage: String?,
	@field:Column(name = "created_at", nullable = false)
	val createdAt: Instant,
) {
	@field:Id
	@field:GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = id
		protected set

	companion object {
		fun create(
			buyerShippingAddressBookId: Long,
			addressName: String?,
			recipientName: String?,
			phoneNumber: String?,
			postalCode: String?,
			address: String?,
			detailAddress: String?,
			deliveryMessage: String?,
			createdAt: Instant,
		): BuyerShippingAddress {
			require(buyerShippingAddressBookId > 0) { "배송지 목록 식별자는 양수여야 합니다." }
			return create(
				buyerShippingAddressBookId,
				BuyerShippingAddressDetails.create(
					addressName,
					recipientName,
					phoneNumber,
					postalCode,
					address,
					detailAddress,
					deliveryMessage,
				),
				createdAt,
			)
		}

		fun create(
			buyerShippingAddressBookId: Long,
			details: BuyerShippingAddressDetails,
			createdAt: Instant,
		): BuyerShippingAddress {
			require(buyerShippingAddressBookId > 0) { "배송지 목록 식별자는 양수여야 합니다." }
			return BuyerShippingAddress(
				null,
				buyerShippingAddressBookId,
				details.addressName,
				details.recipientName,
				details.phoneNumber,
				details.postalCode,
				details.address,
				details.detailAddress,
				details.deliveryMessage,
				createdAt,
			)
		}
	}
}
