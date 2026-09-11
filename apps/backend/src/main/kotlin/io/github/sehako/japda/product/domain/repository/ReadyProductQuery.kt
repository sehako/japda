package io.github.sehako.japda.product.domain.repository

data class ReadyProductQuery(
	val sellerId: Long,
	val sort: ReadyProductSort,
	val cursor: ReadyProductCursorBoundary?,
	val limit: Int,
) {
	init {
		require(cursor == null || sort.accepts(cursor))
	}

	private fun ReadyProductSort.accepts(cursor: ReadyProductCursorBoundary): Boolean = when (this) {
		ReadyProductSort.LATEST,
		ReadyProductSort.OLDEST,
		-> cursor is ReadyProductCursorBoundary.Id

		ReadyProductSort.NAME_ASC,
		ReadyProductSort.NAME_DESC,
		-> cursor is ReadyProductCursorBoundary.Name
	}
}
