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
	name = "buyer_shipping_address_books",
	uniqueConstraints = [UniqueConstraint(name = "buyer_shipping_address_books_buyer_id_unique", columnNames = ["buyer_id"])],
)
class BuyerShippingAddressBook private constructor(
	id: Long?,
	@field:Column(name = "buyer_id", nullable = false, unique = true)
	val buyerId: Long,
	@field:Column(name = "created_at", nullable = false)
	val createdAt: Instant,
) {
	@field:Id
	@field:GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = id
		protected set

	companion object {
		fun create(buyerId: Long, createdAt: Instant): BuyerShippingAddressBook {
			require(buyerId > 0) { "구매자 식별자는 양수여야 합니다." }
			return BuyerShippingAddressBook(null, buyerId, createdAt)
		}
	}
}
