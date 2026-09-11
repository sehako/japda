package io.github.sehako.japda.sale.domain.repository

import java.time.Instant
import java.time.LocalDate

interface BuyerSaleProductQueryRepository {
	fun findAllBySaleDate(saleDate: LocalDate): List<BuyerSaleProductQueryResult>

	fun findDetailBySaleId(saleId: Long): BuyerSaleProductDetailQueryResult?
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

data class BuyerSaleProductDetailQueryResult(
	val saleId: Long,
	val productId: Long,
	val name: String,
	val description: String?,
	val price: Long,
	val quantity: Int,
	val saleDate: LocalDate,
	val images: List<BuyerSaleProductDetailImageQueryResult>,
)

data class BuyerSaleProductDetailImageQueryResult(
	val objectKey: String,
	val displayOrder: Int,
	val isRepresentative: Boolean,
)
