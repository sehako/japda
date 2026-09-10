package io.github.sehako.japda.product.domain

sealed interface ReadyProductCursorBoundary {
	val id: Long

	data class Id(
		override val id: Long,
	) : ReadyProductCursorBoundary

	data class Name(
		val name: String,
		override val id: Long,
	) : ReadyProductCursorBoundary
}
