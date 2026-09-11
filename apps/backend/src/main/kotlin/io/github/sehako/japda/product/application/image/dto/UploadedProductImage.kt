package io.github.sehako.japda.product.application.image.dto

data class UploadedProductImage(
	val objectKey: String,
	val contentType: String,
	val sizeBytes: Long,
	val displayOrder: Int,
	val isRepresentative: Boolean,
)
