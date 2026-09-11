package io.github.sehako.japda.sale.application.response

import java.time.Instant
import java.time.LocalDate

data class SaleResponse(
	val id: Long,
	val productId: Long,
	val sellerId: Long,
	val saleDate: LocalDate,
	val price: Long,
	val quantity: Int,
	val startsAt: Instant,
	val endsAt: Instant,
	val createdAt: Instant,
)
