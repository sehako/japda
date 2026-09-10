package io.github.sehako.japda.sale.domain

import io.github.sehako.japda.sale.exception.SaleErrorCode
import io.github.sehako.japda.sale.exception.SaleException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate

@Entity
@Table(name = "sale_days")
class SaleDay private constructor(
	@field:Id
	@field:Column(name = "sale_date", nullable = false)
	val saleDate: LocalDate,
	@field:Column(nullable = false)
	val capacity: Int,
	registeredCount: Int,
) {
	@field:Column(name = "registered_count", nullable = false)
	var registeredCount: Int = registeredCount
		protected set

	fun reserve() {
		if (registeredCount >= capacity) throw SaleException(SaleErrorCode.CAPACITY_EXCEEDED)
		registeredCount++
	}

	companion object {
		fun create(saleDate: LocalDate, capacity: Int): SaleDay {
			require(capacity > 0) { "판매일별 정원은 양수여야 합니다." }
			return SaleDay(saleDate, capacity, 0)
		}
	}
}
