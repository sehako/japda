package io.github.sehako.japda.shippingaddress.infrastructure.persistence

import io.github.sehako.japda.shippingaddress.domain.model.BuyerShippingAddress
import io.github.sehako.japda.shippingaddress.domain.repository.BuyerShippingAddressRepository
import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressNameDuplicatedPersistenceException
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository

@Repository
class BuyerShippingAddressRepositoryImpl(
	private val jpaRepository: BuyerShippingAddressJpaRepository,
) : BuyerShippingAddressRepository {
	override fun countByBuyerShippingAddressBookId(bookId: Long): Long =
		jpaRepository.countByBuyerShippingAddressBookId(bookId)

	override fun existsByBuyerShippingAddressBookIdAndAddressName(bookId: Long, addressName: String): Boolean =
		jpaRepository.existsByBuyerShippingAddressBookIdAndAddressName(bookId, addressName)

	override fun save(address: BuyerShippingAddress): BuyerShippingAddress = try {
		jpaRepository.saveAndFlush(address)
	} catch (exception: DataIntegrityViolationException) {
		val constraintName = generateSequence<Throwable>(exception) { it.cause }
			.filterIsInstance<ConstraintViolationException>()
			.firstOrNull()
			?.constraintName
		if (constraintName == ADDRESS_NAME_UNIQUE_CONSTRAINT) {
			throw BuyerShippingAddressNameDuplicatedPersistenceException(exception)
		}
		throw exception
	}

	private companion object {
		const val ADDRESS_NAME_UNIQUE_CONSTRAINT = "buyer_shipping_addresses_book_id_address_name_unique"
	}
}
