package io.github.sehako.japda.shippingaddress.application.service

import io.github.sehako.japda.shippingaddress.application.dto.CreateBuyerShippingAddressDto
import io.github.sehako.japda.shippingaddress.application.response.BuyerShippingAddressResponse
import io.github.sehako.japda.shippingaddress.domain.model.BuyerShippingAddressDetails
import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressErrorCode
import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressException
import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressNameDuplicatedPersistenceException
import java.time.Clock
import org.springframework.stereotype.Service

@Service
class BuyerShippingAddressService(
	private val transactionService: BuyerShippingAddressRegistrationTransactionService,
	private val clock: Clock,
) {
	fun create(dto: CreateBuyerShippingAddressDto): BuyerShippingAddressResponse {
		val createdAt = clock.instant()
		val details = BuyerShippingAddressDetails.create(
			dto.addressName,
			dto.recipientName,
			dto.phoneNumber,
			dto.postalCode,
			dto.address,
			dto.detailAddress,
			dto.deliveryMessage,
		)
		return try {
			transactionService.register(dto.buyerId, details, createdAt)
		} catch (_: BuyerShippingAddressNameDuplicatedPersistenceException) {
			throw BuyerShippingAddressException(BuyerShippingAddressErrorCode.NAME_DUPLICATED)
		}
	}
}
