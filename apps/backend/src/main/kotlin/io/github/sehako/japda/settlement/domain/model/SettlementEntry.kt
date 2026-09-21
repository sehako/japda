package io.github.sehako.japda.settlement.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "settlement_entries")
class SettlementEntry private constructor(
	id: Long?,
	@field:Column(name = "payment_id", nullable = false, unique = true)
	val paymentId: Long,
	@field:Column(name = "order_id", nullable = false)
	val orderId: Long,
	@field:Column(name = "sale_id", nullable = false)
	val saleId: Long,
	@field:Column(name = "seller_id", nullable = false)
	val sellerId: Long,
	@field:Column(name = "recipient_user_id", nullable = false)
	val recipientUserId: Long,
	@field:Column(nullable = false)
	val quantity: Int,
	@field:Column(name = "unit_price", nullable = false)
	val unitPrice: Long,
	@field:Column(name = "gross_amount", nullable = false)
	val grossAmount: Long,
	@field:Column(name = "payment_approved_at", nullable = false)
	val paymentApprovedAt: Instant,
	@field:Column(name = "settlement_date", nullable = false)
	val settlementDate: LocalDate,
	@field:Column(name = "created_at", nullable = false)
	val createdAt: Instant,
) {
	@field:Id
	@field:GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = id
		protected set

	fun matches(snapshot: SettlementEntrySnapshot): Boolean =
		paymentId == snapshot.paymentId &&
		orderId == snapshot.orderId &&
		saleId == snapshot.saleId &&
		sellerId == snapshot.sellerId &&
		recipientUserId == snapshot.recipientUserId &&
		quantity == snapshot.quantity &&
		unitPrice == snapshot.unitPrice &&
		grossAmount == snapshot.grossAmount &&
		paymentApprovedAt == snapshot.paymentApprovedAt &&
		settlementDate == snapshot.settlementDate

	companion object {
		fun create(snapshot: SettlementEntrySnapshot, createdAt: Instant): SettlementEntry {
			require(snapshot.paymentId > 0 && snapshot.orderId > 0 && snapshot.saleId > 0)
			require(snapshot.sellerId > 0 && snapshot.recipientUserId > 0)
			require(snapshot.quantity > 0 && snapshot.unitPrice > 0 && snapshot.grossAmount > 0)
			require(Math.multiplyExact(snapshot.quantity.toLong(), snapshot.unitPrice) == snapshot.grossAmount)
			return SettlementEntry(
				id = null,
				paymentId = snapshot.paymentId,
				orderId = snapshot.orderId,
				saleId = snapshot.saleId,
				sellerId = snapshot.sellerId,
				recipientUserId = snapshot.recipientUserId,
				quantity = snapshot.quantity,
				unitPrice = snapshot.unitPrice,
				grossAmount = snapshot.grossAmount,
				paymentApprovedAt = snapshot.paymentApprovedAt,
				settlementDate = snapshot.settlementDate,
				createdAt = createdAt,
			)
		}
	}
}

data class SettlementEntrySnapshot(
	val paymentId: Long,
	val orderId: Long,
	val saleId: Long,
	val sellerId: Long,
	val recipientUserId: Long,
	val quantity: Int,
	val unitPrice: Long,
	val grossAmount: Long,
	val paymentApprovedAt: Instant,
	val settlementDate: LocalDate,
)
