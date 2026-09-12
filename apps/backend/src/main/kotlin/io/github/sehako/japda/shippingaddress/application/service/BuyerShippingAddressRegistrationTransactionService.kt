package io.github.sehako.japda.shippingaddress.application.service

import io.github.sehako.japda.shippingaddress.application.response.BuyerShippingAddressResponse
import io.github.sehako.japda.shippingaddress.application.response.toResponse
import io.github.sehako.japda.shippingaddress.domain.model.BuyerShippingAddress
import io.github.sehako.japda.shippingaddress.domain.model.BuyerShippingAddressDetails
import io.github.sehako.japda.shippingaddress.domain.repository.BuyerShippingAddressBookRepository
import io.github.sehako.japda.shippingaddress.domain.repository.BuyerShippingAddressRepository
import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressErrorCode
import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressException
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BuyerShippingAddressRegistrationTransactionService(
	private val bookRepository: BuyerShippingAddressBookRepository,
	private val addressRepository: BuyerShippingAddressRepository,
) {
	@Transactional
	fun register(buyerId: Long, details: BuyerShippingAddressDetails, createdAt: Instant): BuyerShippingAddressResponse {
		bookRepository.createIfAbsent(buyerId, createdAt)
		val book = checkNotNull(bookRepository.findByBuyerIdForUpdate(buyerId)) {
			"생성한 구매자 배송지 목록을 찾을 수 없습니다."
		}
		val bookId = requireNotNull(book.id)
		if (addressRepository.countByBuyerShippingAddressBookId(bookId) >= MAX_ADDRESS_COUNT) {
			throw BuyerShippingAddressException(BuyerShippingAddressErrorCode.LIMIT_EXCEEDED)
		}
		if (addressRepository.existsByBuyerShippingAddressBookIdAndAddressName(bookId, details.addressName)) {
			throw BuyerShippingAddressException(BuyerShippingAddressErrorCode.NAME_DUPLICATED)
		}
		return addressRepository.save(BuyerShippingAddress.create(bookId, details, createdAt)).toResponse()
	}

	private companion object {
		const val MAX_ADDRESS_COUNT = 10L
	}
}
