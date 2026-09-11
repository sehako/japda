package io.github.sehako.japda.product.application.response

data class ReadyProductResponse(
	val id: Long,
	val name: String,
)

data class ReadyProductPageResponse(
	val items: List<ReadyProductResponse>,
	val nextCursor: String?,
)
