package io.github.sehako.japda.sale.presentation

import tools.jackson.databind.JsonNode

data class CreateSaleRequest(
	val price: JsonNode? = null,
	val quantity: JsonNode? = null,
	val startsAt: JsonNode? = null,
	val endsAt: JsonNode? = null,
)
