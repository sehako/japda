package io.github.sehako.japda.sale.domain.repository

import io.github.sehako.japda.sale.domain.model.Sale

import java.time.LocalDate

interface SaleRepository {
	fun save(sale: Sale): Sale

	fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate): Boolean

	fun findQuantityById(id: Long): Int? = null

	fun findByIdForUpdate(id: Long): Sale?
}
