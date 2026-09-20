package io.github.sehako.japda.order.infrastructure.checkout.cache.key

class RedisCheckoutProductSnapshotKeyFactory(
	private val namespace: String,
) {
	fun snapshot(saleId: Long): String = "$namespace:checkout:product-snapshot:v1:$saleId"
}
