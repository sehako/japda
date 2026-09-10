package io.github.sehako.japda.sale.presentation

import io.github.sehako.japda.sale.application.CreateSaleDto
import java.time.LocalDate
import tools.jackson.databind.annotation.JsonDeserialize

data class CreateSaleRequest(
	@field:JsonDeserialize(using = StrictNullableLongDeserializer::class)
	val productId: Long?,
	val saleDate: LocalDate?,
	@field:JsonDeserialize(using = StrictNullableLongDeserializer::class)
	val price: Long?,
	@field:JsonDeserialize(using = StrictNullableIntDeserializer::class)
	val quantity: Int?,
) {
	fun toDto(sellerId: Long): CreateSaleDto = CreateSaleDto(
		sellerId = sellerId,
		productId = productId,
		saleDate = saleDate,
		price = price,
		quantity = quantity,
	)
}
