package io.github.sehako.japda.product.application.image

import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException

@org.springframework.stereotype.Component
class ProductImageFileValidator {
	fun validate(files: List<ProductImageFile>, representativeIndex: Int): List<ValidatedProductImageFile> {
		if (files.size !in MIN_FILE_COUNT..MAX_FILE_COUNT) {
			throw ProductException(ProductErrorCode.IMAGE_COUNT_INVALID)
		}
		if (representativeIndex !in files.indices) {
			throw ProductException(ProductErrorCode.IMAGE_REPRESENTATIVE_INVALID)
		}

		var totalSize = 0L
		return files.mapIndexed { index, file ->
			if (file.size <= 0) throw ProductException(ProductErrorCode.IMAGE_FILE_INVALID)
			if (file.size > MAX_FILE_SIZE) throw ProductException(ProductErrorCode.IMAGE_SIZE_EXCEEDED)
			totalSize += file.size
			if (totalSize > MAX_TOTAL_SIZE) throw ProductException(ProductErrorCode.IMAGE_SIZE_EXCEEDED)

			val header = file.openStream().use { stream -> stream.readNBytes(SIGNATURE_HEADER_SIZE) }
			ValidatedProductImageFile(file, detectContentType(header), index, index == representativeIndex)
		}
	}

	private fun detectContentType(header: ByteArray): String = when {
		header.startsWith(JPEG_SIGNATURE) -> JPEG_CONTENT_TYPE
		header.startsWith(PNG_SIGNATURE) -> PNG_CONTENT_TYPE
		header.size >= SIGNATURE_HEADER_SIZE &&
			header.copyOfRange(0, 4).contentEquals(WEBP_RIFF_SIGNATURE) &&
			header.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE) -> WEBP_CONTENT_TYPE
		else -> throw ProductException(ProductErrorCode.IMAGE_FORMAT_UNSUPPORTED)
	}

	private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
		size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

	companion object {
		const val MAX_FILE_SIZE = 10L * 1024 * 1024
		const val MAX_TOTAL_SIZE = 50L * 1024 * 1024
		private const val MIN_FILE_COUNT = 1
		private const val MAX_FILE_COUNT = 10
		private const val SIGNATURE_HEADER_SIZE = 12
		private const val JPEG_CONTENT_TYPE = "image/jpeg"
		private const val PNG_CONTENT_TYPE = "image/png"
		private const val WEBP_CONTENT_TYPE = "image/webp"
		private val JPEG_SIGNATURE = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
		private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
		private val WEBP_RIFF_SIGNATURE = "RIFF".encodeToByteArray()
		private val WEBP_SIGNATURE = "WEBP".encodeToByteArray()
	}
}

data class ValidatedProductImageFile(
	val source: ProductImageFile,
	val contentType: String,
	val displayOrder: Int,
	val isRepresentative: Boolean,
)
