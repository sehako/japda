package io.github.sehako.japda.product.presentation

import io.github.sehako.japda.product.application.CreateProductDto
import io.github.sehako.japda.product.domain.InvalidProductException
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

@Component
class CreateProductRequestConverter {

	fun convert(
		sellerIdHeader: String?,
		request: CreateProductRequest,
	): CreateProductDto = CreateProductDto(
		sellerId = parseSellerId(sellerIdHeader),
		name = parseText(request.name),
		description = parseText(request.description),
	)

	private fun parseSellerId(value: String?): Long {
		if (value == null) {
			throw InvalidProductException(mapOf("sellerId" to SELLER_ID_REQUIRED_ERROR))
		}

		return value.toLongOrNull()
			?.takeIf { it > 0 }
			?: throw InvalidProductException(mapOf("sellerId" to SELLER_ID_FORMAT_ERROR))
	}

	private fun parseText(value: JsonNode?): String? {
		if (value == null || value.isNull) return null
		if (!value.isString) {
			throw InvalidProductException(mapOf("request" to REQUEST_BODY_ERROR))
		}

		return value.stringValue()
	}

	companion object {
		private const val SELLER_ID_REQUIRED_ERROR = "판매자 ID는 필수입니다."
		private const val SELLER_ID_FORMAT_ERROR = "판매자 ID는 1 이상의 정수여야 합니다."
		private const val REQUEST_BODY_ERROR = "요청 본문을 읽을 수 없습니다."
	}
}
