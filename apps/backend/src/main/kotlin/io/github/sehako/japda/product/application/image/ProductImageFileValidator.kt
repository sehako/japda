package io.github.sehako.japda.product.application.image

import org.springframework.stereotype.Component

@Component
class ProductImageFileValidator {

	fun validate(
		files: List<ProductImageUploadFile>,
		representativeIndex: Int,
	): List<ValidatedProductImageFile> {
		val requestErrors = linkedMapOf<String, String>()
		if (files.size !in MIN_FILE_COUNT..MAX_FILE_COUNT) {
			requestErrors[FILES_FIELD] = FILE_COUNT_ERROR
		}
		if (representativeIndex !in files.indices) {
			requestErrors[REPRESENTATIVE_INDEX_FIELD] = REPRESENTATIVE_INDEX_ERROR
		}
		if (requestErrors.isNotEmpty()) {
			throw InvalidProductImageUploadException(requestErrors)
		}

		if (files.any { it.sizeBytes > MAX_FILE_SIZE_BYTES }) {
			throw ProductImagePayloadTooLargeException(mapOf(FILES_FIELD to FILE_SIZE_ERROR))
		}
		if (files.sumOf { it.sizeBytes } > MAX_TOTAL_SIZE_BYTES) {
			throw ProductImagePayloadTooLargeException(mapOf(FILES_FIELD to TOTAL_SIZE_ERROR))
		}

		return files.map { file -> validateFile(file) }
	}

	private fun validateFile(file: ProductImageUploadFile): ValidatedProductImageFile {
		if (file.sizeBytes <= 0) {
			throw InvalidProductImageUploadException(mapOf(FILES_FIELD to EMPTY_FILE_ERROR))
		}

		val imageFormat = IMAGE_FORMATS[file.contentType]
			?: throw InvalidProductImageUploadException(mapOf(FILES_FIELD to FILE_FORMAT_ERROR))
		val header = file.openStream().use { input -> input.readNBytes(imageFormat.headerSize) }
		if (!imageFormat.matches(header)) {
			throw InvalidProductImageUploadException(mapOf(FILES_FIELD to FILE_FORMAT_ERROR))
		}

		return ValidatedProductImageFile(
			contentType = checkNotNull(file.contentType),
			sizeBytes = file.sizeBytes,
			extension = imageFormat.extension,
			openStream = file.openStream,
		)
	}

	private data class ImageFormat(
		val extension: String,
		val headerSize: Int,
		val matches: (ByteArray) -> Boolean,
	)

	companion object {
		const val MAX_FILE_COUNT = 10
		const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024
		const val MAX_TOTAL_SIZE_BYTES = 50L * 1024 * 1024

		private const val MIN_FILE_COUNT = 1
		private const val FILES_FIELD = "files"
		private const val REPRESENTATIVE_INDEX_FIELD = "representativeIndex"
		private const val FILE_COUNT_ERROR = "상품 이미지는 1장 이상 10장 이하이어야 합니다."
		private const val REPRESENTATIVE_INDEX_ERROR = "대표 이미지 인덱스가 파일 범위를 벗어났습니다."
		private const val EMPTY_FILE_ERROR = "빈 상품 이미지는 업로드할 수 없습니다."
		private const val FILE_FORMAT_ERROR = "JPEG, PNG 또는 WebP 이미지만 업로드할 수 있습니다."
		private const val FILE_SIZE_ERROR = "상품 이미지 한 장의 크기는 10MiB 이하여야 합니다."
		private const val TOTAL_SIZE_ERROR = "상품 이미지 전체 크기는 50MiB 이하여야 합니다."

		private val IMAGE_FORMATS = mapOf(
			"image/jpeg" to ImageFormat(
				extension = "jpg",
				headerSize = 2,
				matches = { it.contentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) },
			),
			"image/png" to ImageFormat(
				extension = "png",
				headerSize = 8,
				matches = {
					it.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
				},
			),
			"image/webp" to ImageFormat(
				extension = "webp",
				headerSize = 12,
				matches = {
					it.size == 12 &&
						it.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
						it.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII))
				},
			),
		)
	}
}
