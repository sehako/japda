package io.github.sehako.japda.product.presentation.image.adapter

import io.github.sehako.japda.product.application.image.file.ProductImageFile
import java.io.InputStream
import org.springframework.web.multipart.MultipartFile

class MultipartProductImageFile(
	private val multipartFile: MultipartFile,
) : ProductImageFile {
	override val size: Long
		get() = multipartFile.size

	override fun openStream(): InputStream = multipartFile.inputStream
}
