package io.github.sehako.japda.product.application.image

import java.io.InputStream

interface ProductImageFile {
	val size: Long
	fun openStream(): InputStream
}
