package io.github.sehako.japda.order.infrastructure.inventory.key

class RedisInventoryKeyFactory(
    namespace: String,
) {
    private val prefix = "${namespace.trimEnd(':')}:inventory"

    fun stock(saleId: Long): String = "$prefix:{$saleId}:stock"

    fun initializationLock(saleId: Long): String = "$prefix:{$saleId}:initialize-lock"

    fun reservation(saleId: Long, reservationId: String): String =
        "$prefix:{$saleId}:reservation:$reservationId"
}
