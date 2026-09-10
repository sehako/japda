package io.github.sehako.japda.product.presentation.image

import io.github.sehako.japda.product.application.image.ProductImageFile
import java.io.InputStream
import org.springframework.web.multipart.MultipartFile

class MultipartProductImageFile(
	private val multipartFile: MultipartFile,
) : ProductImageFile {
	override val size: Long
		get() = multipartFile.size

	override fun openStream(): InputStream = multipartFile.inputStream
}
