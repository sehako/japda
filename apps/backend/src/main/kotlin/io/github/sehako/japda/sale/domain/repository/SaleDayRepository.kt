package io.github.sehako.japda.sale.domain.repository

import io.github.sehako.japda.sale.domain.model.SaleDay
import java.time.LocalDate

interface SaleDayRepository {
	fun createIfAbsent(saleDate: LocalDate, capacity: Int)

	fun findBySaleDateForUpdate(saleDate: LocalDate): SaleDay?

	fun save(saleDay: SaleDay): SaleDay
}
