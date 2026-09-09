package io.github.sehako.japda.product.application.image

import java.util.UUID

class ProductImageObjectKeyGenerator internal constructor(
	private val prefix: String,
	private val uuidSupplier: () -> UUID,
) {
	constructor(prefix: String) : this(prefix, UUID::randomUUID)

	fun generate(productId: Long, extension: String): String {
		val normalizedPrefix = prefix.trim('/')
		val pathPrefix = if (normalizedPrefix.isEmpty()) "" else "$normalizedPrefix/"
		return "${pathPrefix}products/$productId/${uuidSupplier()}.$extension"
	}
}
