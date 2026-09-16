package io.github.sehako.japda.batch.settlement.application.dto

import java.time.OffsetDateTime

data class CreateSettlementDetailCommand(
	val settlementRunId: Long,
	val paymentId: Long,
	val orderId: Long,
	val saleId: Long,
	val sellerId: Long,
	val recipientUserId: Long,
	val quantity: Int,
	val unitPrice: Long,
	val grossAmount: Long,
	val paymentApprovedAt: OffsetDateTime,
)
