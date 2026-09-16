package io.github.sehako.japda.batch.settlement.application.processor

import io.github.sehako.japda.batch.settlement.application.dto.CreateSettlementDetailCommand
import io.github.sehako.japda.batch.settlement.application.dto.SettlementPaymentProjection
import io.github.sehako.japda.batch.settlement.exception.SettlementCollectionErrorType
import io.github.sehako.japda.batch.settlement.exception.SettlementCollectionException
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

@DisplayName("정산 결제 Processor")
class SettlementPaymentProcessorTest {
	private val processor = SettlementPaymentProcessor(settlementRunId = 10L)

	@Test
	@DisplayName("정상 결제를 구매 당시 주문 스냅샷으로 변환한다")
	fun 정상_결제를_구매_당시_주문_스냅샷으로_변환한다() {
		val projection = validProjection()

		val result = processor.process(projection)

		assertEquals(
			CreateSettlementDetailCommand(
				settlementRunId = 10L,
				paymentId = 1L,
				orderId = 2L,
				saleId = 3L,
				sellerId = 4L,
				recipientUserId = 5L,
				quantity = 2,
				unitPrice = 5_000L,
				grossAmount = 10_000L,
				paymentApprovedAt = OffsetDateTime.parse("2026-09-15T01:23:45Z"),
			),
			result,
		)
	}

	@ParameterizedTest(name = "{1}")
	@MethodSource("invalidProjections")
	@DisplayName("정산 데이터가 유효하지 않으면 paymentId와 오류 분류로 실패한다")
	fun 정산_데이터가_유효하지_않으면_paymentId와_오류_분류로_실패한다(
		projection: SettlementPaymentProjection,
		expectedErrorType: SettlementCollectionErrorType,
	) {
		val exception = assertFailsWith<SettlementCollectionException> {
			processor.process(projection)
		}

		assertEquals(1L, exception.paymentId)
		assertEquals(expectedErrorType, exception.errorType)
		assertEquals("paymentId=1, errorType=$expectedErrorType", exception.message)
	}

	companion object {
		@JvmStatic
		fun invalidProjections() = listOf(
			Arguments.of(validProjection().copy(orderId = null), SettlementCollectionErrorType.ORDER_MISSING),
			Arguments.of(validProjection().copy(saleId = null), SettlementCollectionErrorType.SALE_MISSING),
			Arguments.of(validProjection().copy(sellerId = null), SettlementCollectionErrorType.SALE_MISSING),
			Arguments.of(validProjection().copy(orderStatus = "PENDING_PAYMENT"), SettlementCollectionErrorType.ORDER_NOT_PAID),
			Arguments.of(validProjection().copy(quantity = null), SettlementCollectionErrorType.ORDER_QUANTITY_INVALID),
			Arguments.of(validProjection().copy(quantity = 0), SettlementCollectionErrorType.ORDER_QUANTITY_INVALID),
			Arguments.of(validProjection().copy(unitPrice = null), SettlementCollectionErrorType.ORDER_UNIT_PRICE_INVALID),
			Arguments.of(validProjection().copy(unitPrice = 0L), SettlementCollectionErrorType.ORDER_UNIT_PRICE_INVALID),
			Arguments.of(validProjection().copy(totalPrice = null), SettlementCollectionErrorType.ORDER_TOTAL_PRICE_INVALID),
			Arguments.of(validProjection().copy(totalPrice = 0L), SettlementCollectionErrorType.ORDER_TOTAL_PRICE_INVALID),
			Arguments.of(validProjection().copy(requestedAmount = 9_999L), SettlementCollectionErrorType.PAYMENT_AMOUNT_MISMATCH),
			Arguments.of(validProjection().copy(recipientUserId = null), SettlementCollectionErrorType.RECIPIENT_USER_MISSING),
		)

		private fun validProjection(): SettlementPaymentProjection = SettlementPaymentProjection(
			paymentId = 1L,
			requestedAmount = 10_000L,
			paymentApprovedAt = Instant.parse("2026-09-15T01:23:45Z"),
			orderId = 2L,
			orderStatus = "PAID",
			saleId = 3L,
			sellerId = 4L,
			recipientUserId = 5L,
			quantity = 2,
			unitPrice = 5_000L,
			totalPrice = 10_000L,
		)
	}
}
