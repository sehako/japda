package io.github.sehako.japda.sale.domain.repository

import io.github.sehako.japda.sale.domain.model.Sale

import java.time.LocalDate

interface SaleRepository {
	fun save(sale: Sale): Sale

	fun increaseCommittedQuantity(saleId: Long, quantity: Int): SaleCommittedQuantityIncreaseResult =
		throw UnsupportedOperationException("committed quantity 증가가 구현되지 않았습니다.")

	fun decreaseCommittedQuantity(saleId: Long, quantity: Int): Boolean =
		throw UnsupportedOperationException("committed quantity 감소가 구현되지 않았습니다.")

	fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate): Boolean

	fun findQuantityById(id: Long): Int? = null

	fun findById(id: Long): Sale? = null

	fun findByIdForUpdate(id: Long): Sale?
}

sealed interface SaleCommittedQuantityIncreaseResult {
	data object Increased : SaleCommittedQuantityIncreaseResult

	data class Insufficient(val remainingQuantity: Int) : SaleCommittedQuantityIncreaseResult

	data object MissingSale : SaleCommittedQuantityIncreaseResult
}
