package io.github.sehako.japda.order.infrastructure.inventory.redis

import io.github.sehako.japda.order.application.inventory.InventoryReservation
import io.github.sehako.japda.order.application.inventory.InventoryReservationResult
import io.github.sehako.japda.order.application.inventory.InventoryReservationToken

class DisabledInventoryReservation : InventoryReservation {
    override fun reserve(saleId: Long, quantity: Int): InventoryReservationResult = InventoryReservationResult.Fallback

    override fun restore(token: InventoryReservationToken) = Unit

    override fun invalidate(token: InventoryReservationToken) = Unit
}
