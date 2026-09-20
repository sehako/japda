package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.repository.CheckoutProductSnapshot
import io.github.sehako.japda.order.domain.repository.CheckoutProductSnapshotQuery
import io.github.sehako.japda.order.domain.repository.CheckoutShippingAddress
import io.github.sehako.japda.order.domain.repository.CheckoutShippingAddressQuery
import org.springframework.stereotype.Repository

@Repository
internal class CheckoutProductSnapshotQueryRepositoryImpl(
	private val jpaRepository: CheckoutJpaRepository,
) : CheckoutProductSnapshotQuery {
	override fun findBySaleId(saleId: Long): CheckoutProductSnapshot? =
		jpaRepository.findProductSnapshot(saleId)?.let { row ->
			CheckoutProductSnapshot(
				saleId = row.saleId,
				productName = row.productName,
				representativeImageObjectKey = row.representativeImageObjectKey,
				unitPrice = row.unitPrice,
			)
		}
}

@Repository
internal class CheckoutShippingAddressQueryRepositoryImpl(
	private val jpaRepository: CheckoutJpaRepository,
) : CheckoutShippingAddressQuery {
	override fun findByBuyerId(buyerId: Long): List<CheckoutShippingAddress> =
		jpaRepository.findShippingAddresses(buyerId).map { row ->
			CheckoutShippingAddress(
				shippingAddressId = row.shippingAddressId,
				addressName = row.addressName,
				recipientName = row.recipientName,
				phoneNumber = row.phoneNumber,
				postalCode = row.postalCode,
				address = row.address,
				detailAddress = row.detailAddress,
				deliveryMessage = row.deliveryMessage,
			)
		}
}
