package io.github.sehako.japda.shippingaddress.infrastructure.persistence

import io.github.sehako.japda.shippingaddress.domain.model.BuyerShippingAddressBook
import jakarta.persistence.LockModeType
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface BuyerShippingAddressBookJpaRepository : JpaRepository<BuyerShippingAddressBook, Long> {
	@Modifying
	@Query(
		value = """INSERT INTO buyer_shipping_address_books (buyer_id, created_at)
			VALUES (:buyerId, :createdAt)
			ON CONFLICT (buyer_id) DO NOTHING""",
		nativeQuery = true,
	)
	fun createIfAbsent(@Param("buyerId") buyerId: Long, @Param("createdAt") createdAt: Instant)

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select book from BuyerShippingAddressBook book where book.buyerId = :buyerId")
	fun findByBuyerIdForUpdate(@Param("buyerId") buyerId: Long): BuyerShippingAddressBook?
}
