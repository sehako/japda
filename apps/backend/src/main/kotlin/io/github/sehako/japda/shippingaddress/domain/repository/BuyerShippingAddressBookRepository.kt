package io.github.sehako.japda.shippingaddress.domain.repository

import io.github.sehako.japda.shippingaddress.domain.model.BuyerShippingAddressBook
import java.time.Instant

interface BuyerShippingAddressBookRepository {
	fun createIfAbsent(buyerId: Long, createdAt: Instant)

	fun findByBuyerIdForUpdate(buyerId: Long): BuyerShippingAddressBook?
}
