package io.github.sehako.japda.shippingaddress.infrastructure.persistence

import io.github.sehako.japda.shippingaddress.domain.model.BuyerShippingAddressBook
import io.github.sehako.japda.shippingaddress.domain.repository.BuyerShippingAddressBookRepository
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class BuyerShippingAddressBookRepositoryImpl(
	private val jpaRepository: BuyerShippingAddressBookJpaRepository,
) : BuyerShippingAddressBookRepository {
	override fun createIfAbsent(buyerId: Long, createdAt: Instant) = jpaRepository.createIfAbsent(buyerId, createdAt)

	override fun findByBuyerIdForUpdate(buyerId: Long): BuyerShippingAddressBook? =
		jpaRepository.findByBuyerIdForUpdate(buyerId)
}
