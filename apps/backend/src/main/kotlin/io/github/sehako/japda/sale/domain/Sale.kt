package io.github.sehako.japda.sale.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "sales")
class Sale internal constructor(
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	val id: Long? = null,

	@Column(name = "product_id", nullable = false)
	val productId: Long,

	@Column(nullable = false)
	val price: Long,

	@Column(name = "initial_quantity", nullable = false)
	val initialQuantity: Long,

	@Column(name = "remaining_quantity", nullable = false)
	val remainingQuantity: Long,

	@Column(name = "starts_at", nullable = false)
	val startsAt: Instant,

	@Column(name = "ends_at", nullable = false)
	val endsAt: Instant,

	@Column(name = "created_at", nullable = false)
	val createdAt: Instant,
) {
	companion object {
		private const val PRICE_ERROR = "판매 가격은 1 이상의 정수여야 합니다."
		private const val QUANTITY_ERROR = "판매 수량은 1 이상의 정수여야 합니다."
		private const val PERIOD_ORDER_ERROR = "판매 종료 시각은 판매 시작 시각보다 늦어야 합니다."
		private const val FUTURE_END_ERROR = "판매 종료 시각은 현재 시각보다 미래여야 합니다."

		fun create(
			productId: Long,
			price: Long,
			quantity: Long,
			startsAt: Instant,
			endsAt: Instant,
			now: Instant,
		): Sale {
			val errors = linkedMapOf<String, String>()

			if (price <= 0) {
				errors["price"] = PRICE_ERROR
			}
			if (quantity <= 0) {
				errors["quantity"] = QUANTITY_ERROR
			}
			when {
				!endsAt.isAfter(startsAt) -> errors["endsAt"] = PERIOD_ORDER_ERROR
				!endsAt.isAfter(now) -> errors["endsAt"] = FUTURE_END_ERROR
			}

			if (errors.isNotEmpty()) {
				throw InvalidSaleException(errors)
			}

			return Sale(
				productId = productId,
				price = price,
				initialQuantity = quantity,
				remainingQuantity = quantity,
				startsAt = startsAt,
				endsAt = endsAt,
				createdAt = now,
			)
		}
	}

	fun statusAt(now: Instant): SaleStatus = when {
		!now.isBefore(endsAt) -> SaleStatus.ENDED
		remainingQuantity == 0L -> SaleStatus.SOLD_OUT
		now.isBefore(startsAt) -> SaleStatus.SCHEDULED
		else -> SaleStatus.ON_SALE
	}
}
