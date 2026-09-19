package io.github.sehako.japda.order.application.inventory

interface SoldOutInventoryMarker {
	fun isSoldOut(saleId: Long): Boolean

	fun markSoldOut(saleId: Long)
}
