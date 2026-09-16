package io.github.sehako.japda.batch.settlement.application.processor

import io.github.sehako.japda.batch.settlement.application.dto.CreateSettlementDetailCommand
import io.github.sehako.japda.batch.settlement.application.dto.SettlementPaymentProjection
import io.github.sehako.japda.batch.settlement.exception.SettlementCollectionErrorType
import io.github.sehako.japda.batch.settlement.exception.SettlementCollectionException
import java.time.ZoneOffset
import org.springframework.batch.infrastructure.item.ItemProcessor

class SettlementPaymentProcessor(
	private val settlementRunId: Long,
) : ItemProcessor<SettlementPaymentProjection, CreateSettlementDetailCommand> {
	override fun process(item: SettlementPaymentProjection): CreateSettlementDetailCommand {
		val orderId = item.orderId ?: fail(item, SettlementCollectionErrorType.ORDER_MISSING)
		val saleId = item.saleId ?: fail(item, SettlementCollectionErrorType.SALE_MISSING)
		val sellerId = item.sellerId ?: fail(item, SettlementCollectionErrorType.SALE_MISSING)
		if (item.orderStatus != PAID_ORDER_STATUS) {
			fail(item, SettlementCollectionErrorType.ORDER_NOT_PAID)
		}
		val quantity = item.quantity?.takeIf { it > 0 }
			?: fail(item, SettlementCollectionErrorType.ORDER_QUANTITY_INVALID)
		val unitPrice = item.unitPrice?.takeIf { it > 0 }
			?: fail(item, SettlementCollectionErrorType.ORDER_UNIT_PRICE_INVALID)
		val totalPrice = item.totalPrice?.takeIf { it > 0 }
			?: fail(item, SettlementCollectionErrorType.ORDER_TOTAL_PRICE_INVALID)
		if (totalPrice != item.requestedAmount) {
			fail(item, SettlementCollectionErrorType.PAYMENT_AMOUNT_MISMATCH)
		}
		val recipientUserId = item.recipientUserId
			?: fail(item, SettlementCollectionErrorType.RECIPIENT_USER_MISSING)

		return CreateSettlementDetailCommand(
			settlementRunId = settlementRunId,
			paymentId = item.paymentId,
			orderId = orderId,
			saleId = saleId,
			sellerId = sellerId,
			recipientUserId = recipientUserId,
			quantity = quantity,
			unitPrice = unitPrice,
			grossAmount = totalPrice,
			paymentApprovedAt = item.paymentApprovedAt.atOffset(ZoneOffset.UTC),
		)
	}

	private fun fail(
		item: SettlementPaymentProjection,
		errorType: SettlementCollectionErrorType,
	): Nothing = throw SettlementCollectionException(item.paymentId, errorType)

	private companion object {
		const val PAID_ORDER_STATUS = "PAID"
	}
}
