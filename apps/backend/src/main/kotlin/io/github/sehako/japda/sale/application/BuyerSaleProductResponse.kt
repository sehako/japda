package io.github.sehako.japda.sale.application

import java.time.Instant
import java.time.LocalDate

data class BuyerSaleProductListResponse(
	val sales: List<BuyerSaleProductResponse>,
)

data class BuyerSaleProductResponse(
	val saleId: Long,
	val productId: Long,
	val name: String,
	val description: String?,
	val price: Long,
	val quantity: Int,
	val saleDate: LocalDate,
	val startsAt: Instant,
	val endsAt: Instant,
	val status: BuyerSaleStatus,
	val representativeImagePath: String,
)

data class BuyerSaleProductDetailResponse(
	val saleId: Long,
	val productId: Long,
	val name: String,
	val description: String?,
	val price: Long,
	val quantity: Int,
	val saleDate: LocalDate,
	val startsAt: Instant,
	val endsAt: Instant,
	val status: BuyerSaleStatus,
	val images: List<BuyerSaleProductDetailImageResponse>,
)

data class BuyerSaleProductDetailImageResponse(
	val path: String,
	val displayOrder: Int,
	val isRepresentative: Boolean,
)

enum class BuyerSaleStatus {
	UPCOMING,
	ON_SALE,
	ENDED,
}
