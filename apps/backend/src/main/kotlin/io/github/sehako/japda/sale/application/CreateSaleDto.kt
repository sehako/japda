package io.github.sehako.japda.sale.application

import java.time.LocalDate

data class CreateSaleDto(
	val sellerId: Long,
	val productId: Long?,
	val saleDate: LocalDate?,
	val price: Long?,
	val quantity: Int?,
)
