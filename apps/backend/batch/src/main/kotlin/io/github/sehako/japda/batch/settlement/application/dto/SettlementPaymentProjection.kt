package io.github.sehako.japda.batch.settlement.application.dto

import java.time.Instant

data class SettlementPaymentProjection(
	val paymentId: Long,
	val entryId: Long = paymentId,
	val requestedAmount: Long,
	val paymentApprovedAt: Instant,
	val orderId: Long?,
	val orderStatus: String?,
	val saleId: Long?,
	val sellerId: Long?,
	val recipientUserId: Long?,
	val quantity: Int?,
	val unitPrice: Long?,
	val totalPrice: Long?,
)
