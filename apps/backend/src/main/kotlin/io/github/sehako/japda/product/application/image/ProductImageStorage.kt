package io.github.sehako.japda.product.application.image

import java.io.InputStream

interface ProductImageStorage {
	fun upload(objectKey: String, contentType: String, sizeBytes: Long, inputStream: InputStream)
	fun delete(objectKey: String)
}
