package io.github.sehako.japda.product.presentation

import tools.jackson.databind.JsonNode

data class CreateProductRequest(
	val name: JsonNode? = null,
	val description: JsonNode? = null,
)
