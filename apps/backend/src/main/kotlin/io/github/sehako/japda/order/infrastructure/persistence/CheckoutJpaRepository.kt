package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.model.Order
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param

internal interface CheckoutJpaRepository : Repository<Order, Long> {
	@Query(
		value = """
			SELECT s.id AS "saleId",
			       p.name AS "productName",
			       pi.object_key AS "representativeImageObjectKey",
			       s.price AS "unitPrice"
			FROM sales s
			LEFT JOIN products p ON p.id = s.product_id
			LEFT JOIN product_images pi ON pi.product_id = p.id AND pi.is_representative = true
			WHERE s.id = :saleId
		""",
		nativeQuery = true,
	)
	fun findProductSnapshot(@Param("saleId") saleId: Long): CheckoutProductSnapshotProjection?

	@Query(
		value = """
			SELECT a.id AS "shippingAddressId",
			       a.address_name AS "addressName",
			       a.recipient_name AS "recipientName",
			       a.phone_number AS "phoneNumber",
			       a.postal_code AS "postalCode",
			       a.address AS "address",
			       a.detail_address AS "detailAddress",
			       a.delivery_message AS "deliveryMessage"
			FROM buyer_shipping_address_books b
			JOIN buyer_shipping_addresses a ON a.buyer_shipping_address_book_id = b.id
			WHERE b.buyer_id = :buyerId
			ORDER BY a.id ASC
		""",
		nativeQuery = true,
	)
	fun findShippingAddresses(@Param("buyerId") buyerId: Long): List<CheckoutShippingAddressProjection>
}

internal interface CheckoutProductSnapshotProjection {
	val saleId: Long
	val productName: String?
	val representativeImageObjectKey: String?
	val unitPrice: Long
}

internal interface CheckoutShippingAddressProjection {
	val shippingAddressId: Long?
	val addressName: String?
	val recipientName: String?
	val phoneNumber: String?
	val postalCode: String?
	val address: String?
	val detailAddress: String?
	val deliveryMessage: String?
}
