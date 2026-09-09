package io.github.sehako.japda.sale.application

import java.time.Instant

data class CreateSaleDto(
	val productId: Long,
	val sellerId: Long,
	val price: Long,
	val quantity: Long,
	val startsAt: Instant,
	val endsAt: Instant,
)
