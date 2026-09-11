package io.github.sehako.japda.sale.domain

import java.time.LocalDate

interface SaleRepository {
	fun save(sale: Sale): Sale

	fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate): Boolean

	fun findByIdForUpdate(id: Long): Sale?
}
