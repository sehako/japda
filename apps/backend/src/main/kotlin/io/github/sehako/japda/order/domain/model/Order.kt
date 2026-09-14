package io.github.sehako.japda.order.domain.model

import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Entity
@Table(
	name = "orders",
	uniqueConstraints = [UniqueConstraint(name = "orders_buyer_id_idempotency_key_unique", columnNames = ["buyer_id", "idempotency_key"])],
)
class Order private constructor(
	id: Long?,
	@field:Column(name = "payment_order_id", nullable = false, length = 64, unique = true)
	val paymentOrderId: String,
	@field:Column(name = "sale_id", nullable = false)
	val saleId: Long,
	@field:Column(name = "buyer_id", nullable = false)
	val buyerId: Long,
	@field:Column(name = "idempotency_key", nullable = false)
	val idempotencyKey: UUID,
	@field:Column(nullable = false)
	val quantity: Int,
	@field:Column(name = "product_name", nullable = false, length = 100)
	val productName: String,
	@field:Column(name = "unit_price", nullable = false)
	val unitPrice: Long,
	@field:Column(name = "total_price", nullable = false)
	val totalPrice: Long,
	status: OrderStatus,
	@field:Embedded
	val shippingAddress: ShippingAddress,
	@field:Column(name = "created_at", nullable = false)
	val createdAt: Instant,
	@field:Column(name = "expires_at", nullable = false)
	val expiresAt: Instant,
) {
	@field:Id
	@field:GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = id
		protected set

	@field:Enumerated(EnumType.STRING)
	@field:Column(nullable = false, length = 30)
	var status: OrderStatus = status
		protected set

	fun markPaid() {
		check(status == OrderStatus.PENDING_PAYMENT) { "결제 대기 주문만 완료할 수 있습니다." }
		status = OrderStatus.PAID
	}

	fun matches(request: OrderRequest): Boolean =
		buyerId == request.buyerId &&
			idempotencyKey == request.idempotencyKey &&
			saleId == request.saleId &&
			quantity == request.quantity &&
			shippingAddress == request.shippingAddress

	companion object {
		private val RESERVATION_DURATION: Duration = Duration.ofMinutes(3)

		fun create(request: OrderRequest, productName: String, unitPrice: Long, createdAt: Instant): Order {
			val totalPrice = try {
				Math.multiplyExact(unitPrice, request.quantity.toLong())
			} catch (_: ArithmeticException) {
				throw OrderException(OrderErrorCode.TOTAL_PRICE_INVALID)
			}
			if (unitPrice <= 0 || totalPrice <= 0) throw OrderException(OrderErrorCode.TOTAL_PRICE_INVALID)
			return Order(
				id = null,
				paymentOrderId = UUID.randomUUID().toString(),
				saleId = request.saleId,
				buyerId = request.buyerId,
				idempotencyKey = request.idempotencyKey,
				quantity = request.quantity,
				productName = productName,
				unitPrice = unitPrice,
				totalPrice = totalPrice,
				status = OrderStatus.PENDING_PAYMENT,
				shippingAddress = request.shippingAddress,
				createdAt = createdAt,
				expiresAt = createdAt.plus(RESERVATION_DURATION),
			)
		}
	}
}
