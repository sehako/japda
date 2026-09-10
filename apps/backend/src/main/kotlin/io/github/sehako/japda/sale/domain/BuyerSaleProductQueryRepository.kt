package io.github.sehako.japda.sale.domain

import java.time.Instant
import java.time.LocalDate

interface BuyerSaleProductQueryRepository {
	fun findAllBySaleDate(saleDate: LocalDate): List<BuyerSaleProductQueryResult>
}

data class BuyerSaleProductQueryResult(
	val saleId: Long,
	val productId: Long,
	val name: String,
	val description: String?,
	val price: Long,
	val quantity: Int,
	val saleDate: LocalDate,
	val createdAt: Instant,
	val representativeImageObjectKey: String,
)
