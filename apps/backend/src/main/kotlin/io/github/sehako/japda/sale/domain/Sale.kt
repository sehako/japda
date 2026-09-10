package io.github.sehako.japda.sale.domain

import io.github.sehako.japda.sale.exception.SaleErrorCode
import io.github.sehako.japda.sale.exception.SaleException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Entity
@Table(name = "sales")
class Sale private constructor(
	id: Long?,
	@field:Column(name = "product_id", nullable = false)
	val productId: Long,
	@field:Column(name = "seller_id", nullable = false)
	val sellerId: Long,
	@field:Column(name = "sale_date", nullable = false)
	val saleDate: LocalDate,
	@field:Column(nullable = false)
	val price: Long,
	@field:Column(nullable = false)
	val quantity: Int,
	@field:Column(name = "created_at", nullable = false)
	val createdAt: Instant,
) {
	@field:Id
	@field:GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = id
		protected set

	@get:Transient
	val startsAt: Instant
		get() = saleDate.atStartOfDay(SALE_ZONE).toInstant()

	@get:Transient
	val endsAt: Instant
		get() = saleDate.plusDays(1).atStartOfDay(SALE_ZONE).toInstant()

	companion object {
		private val SALE_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

		fun create(
			productId: Long?,
			sellerId: Long,
			saleDate: LocalDate?,
			price: Long?,
			quantity: Int?,
			createdAt: Instant,
		): Sale {
			if (sellerId <= 0) throw SaleException(SaleErrorCode.SELLER_ID_INVALID)
			if (productId == null || productId <= 0) throw SaleException(SaleErrorCode.PRODUCT_ID_INVALID)
			if (saleDate == null) throw SaleException(SaleErrorCode.DATE_REQUIRED)
			if (price == null || price <= 0) throw SaleException(SaleErrorCode.PRICE_INVALID)
			if (quantity == null || quantity <= 0) throw SaleException(SaleErrorCode.QUANTITY_INVALID)

			return Sale(null, productId, sellerId, saleDate, price, quantity, createdAt)
		}

		fun validateRegistrationTime(saleDate: LocalDate, now: Instant) {
			val opensAt = saleDate.minusDays(1).atTime(9, 0).atZone(SALE_ZONE).toInstant()
			val closesAt = saleDate.atStartOfDay(SALE_ZONE).toInstant()
			if (now < opensAt || now >= closesAt) {
				throw SaleException(SaleErrorCode.REGISTRATION_CLOSED)
			}
		}
	}
}
