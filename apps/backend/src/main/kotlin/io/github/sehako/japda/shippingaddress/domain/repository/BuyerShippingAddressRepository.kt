package io.github.sehako.japda.shippingaddress.domain.repository

import io.github.sehako.japda.shippingaddress.domain.model.BuyerShippingAddress

interface BuyerShippingAddressRepository {
	fun countByBuyerShippingAddressBookId(bookId: Long): Long

	fun existsByBuyerShippingAddressBookIdAndAddressName(bookId: Long, addressName: String): Boolean

	fun save(address: BuyerShippingAddress): BuyerShippingAddress
}
