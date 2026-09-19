package io.github.sehako.japda.order.infrastructure.inventory.key

class RedisInventoryKeyFactory(
    namespace: String,
) {
    private val prefix = "${namespace.trimEnd(':')}:inventory"

    fun soldOut(saleId: Long): String = "$prefix:$saleId:sold-out"
}
