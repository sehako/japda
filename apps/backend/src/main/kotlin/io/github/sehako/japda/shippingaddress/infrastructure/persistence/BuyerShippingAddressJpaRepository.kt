package io.github.sehako.japda.shippingaddress.infrastructure.persistence

import io.github.sehako.japda.shippingaddress.domain.model.BuyerShippingAddress
import org.springframework.data.jpa.repository.JpaRepository

interface BuyerShippingAddressJpaRepository : JpaRepository<BuyerShippingAddress, Long> {
	fun countByBuyerShippingAddressBookId(bookId: Long): Long

	fun existsByBuyerShippingAddressBookIdAndAddressName(bookId: Long, addressName: String): Boolean
}
