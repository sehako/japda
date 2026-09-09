package io.github.sehako.japda.product.application.image

import java.io.InputStream

data class ProductImageUploadFile(
	val contentType: String?,
	val sizeBytes: Long,
	val openStream: () -> InputStream,
)

data class ValidatedProductImageFile(
	val contentType: String,
	val sizeBytes: Long,
	val extension: String,
	val openStream: () -> InputStream,
)
