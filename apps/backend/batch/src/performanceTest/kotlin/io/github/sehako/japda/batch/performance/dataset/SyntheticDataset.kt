package io.github.sehako.japda.batch.performance.dataset

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class SyntheticDataset(
	val saleDate: LocalDate,
	val saleDayCapacity: Int,
	val sellers: List<SyntheticSeller>,
	val orders: List<SyntheticOrder>,
	val payments: List<SyntheticPayment>,
	val expectedSettlement: ExpectedSettlement,
)

data class SyntheticSeller(
	val sellerId: Long,
	val userId: Long,
	val productId: Long,
	val saleId: Long,
	val providerSubject: String,
	val email: String,
	val createdAt: Instant,
)

data class SyntheticOrder(
	val id: Long,
	val saleId: Long,
	val sellerId: Long,
	val buyerId: Long,
	val idempotencyKey: UUID,
	val paymentOrderId: String,
	val grossAmount: Long,
	val createdAt: Instant,
	val expiresAt: Instant,
)

data class SyntheticPayment(
	val id: Long,
	val orderId: Long,
	val paymentKey: String,
	val tossIdempotencyKey: String,
	val grossAmount: Long,
	val createdAt: Instant,
	val approvedAt: Instant,
)

data class ExpectedSettlement(
	val detailCount: Long,
	val sellerSettlementCount: Long,
	val grossAmount: Long,
	val platformFeeAmount: Long,
	val netAmount: Long,
	val sellers: List<ExpectedSellerSettlement>,
)

data class ExpectedSellerSettlement(
	val sellerId: Long,
	val recipientUserId: Long,
	val orderCount: Int,
	val grossAmount: Long,
	val platformFeeAmount: Long,
	val netAmount: Long,
)
