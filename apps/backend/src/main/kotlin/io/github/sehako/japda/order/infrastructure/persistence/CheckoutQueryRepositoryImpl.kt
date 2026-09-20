package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.repository.CheckoutQueryRepository
import io.github.sehako.japda.order.domain.repository.CheckoutQueryRow
import org.springframework.stereotype.Repository

@Repository
internal class CheckoutQueryRepositoryImpl(
	private val jpaRepository: CheckoutJpaRepository,
) : CheckoutQueryRepository {
	override fun findBySaleIdAndBuyerId(saleId: Long, buyerId: Long): List<CheckoutQueryRow> =
		jpaRepository.findRows(saleId, buyerId).map { row ->
			CheckoutQueryRow(
				saleId = row.saleId,
				productName = row.productName,
				representativeImageObjectKey = row.representativeImageObjectKey,
				unitPrice = row.unitPrice,
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
