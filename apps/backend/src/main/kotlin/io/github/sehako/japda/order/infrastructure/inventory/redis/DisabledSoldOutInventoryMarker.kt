package io.github.sehako.japda.order.infrastructure.inventory.redis

import io.github.sehako.japda.order.application.inventory.SoldOutInventoryMarker

class DisabledSoldOutInventoryMarker : SoldOutInventoryMarker {
	override fun isSoldOut(saleId: Long): Boolean = false

	override fun markSoldOut(saleId: Long) = Unit
}
