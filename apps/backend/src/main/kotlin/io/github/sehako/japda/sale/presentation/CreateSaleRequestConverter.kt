package io.github.sehako.japda.sale.presentation

import io.github.sehako.japda.sale.application.CreateSaleDto
import io.github.sehako.japda.sale.domain.InvalidSaleException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

@Component
class CreateSaleRequestConverter {

	fun convert(
		sellerIdHeader: String?,
		productId: Long,
		request: CreateSaleRequest,
	): CreateSaleDto {
		val errors = linkedMapOf<String, String>()
		val sellerId = parseSellerId(sellerIdHeader, errors)
		validateProductId(productId, errors)
		val price = parsePositiveLong("price", request.price, PRICE_REQUIRED_ERROR, PRICE_FORMAT_ERROR, errors)
		val quantity = parsePositiveLong(
			"quantity",
			request.quantity,
			QUANTITY_REQUIRED_ERROR,
			QUANTITY_FORMAT_ERROR,
			errors,
		)
		val startsAt = parseInstant(
			"startsAt",
			request.startsAt,
			STARTS_AT_REQUIRED_ERROR,
			STARTS_AT_FORMAT_ERROR,
			errors,
		)
		val endsAt = parseInstant(
			"endsAt",
			request.endsAt,
			ENDS_AT_REQUIRED_ERROR,
			ENDS_AT_FORMAT_ERROR,
			errors,
		)

		if (errors.isNotEmpty()) {
			throw InvalidSaleException(errors)
		}

		return CreateSaleDto(
			productId = productId,
			sellerId = requireNotNull(sellerId),
			price = requireNotNull(price),
			quantity = requireNotNull(quantity),
			startsAt = requireNotNull(startsAt),
			endsAt = requireNotNull(endsAt),
		)
	}

	private fun parseSellerId(value: String?, errors: MutableMap<String, String>): Long? {
		if (value == null) {
			errors["sellerId"] = SELLER_ID_REQUIRED_ERROR
			return null
		}

		return value.toLongOrNull()
			?.takeIf { it > 0 }
			?: run {
				errors["sellerId"] = SELLER_ID_FORMAT_ERROR
				null
			}
	}

	private fun validateProductId(productId: Long, errors: MutableMap<String, String>) {
		if (productId <= 0) {
			errors["productId"] = PRODUCT_ID_FORMAT_ERROR
		}
	}

	private fun parsePositiveLong(
		field: String,
		value: JsonNode?,
		requiredError: String,
		formatError: String,
		errors: MutableMap<String, String>,
	): Long? {
		if (value == null || value.isNull) {
			errors[field] = requiredError
			return null
		}
		if (!value.isIntegralNumber || !value.canConvertToLong()) {
			errors[field] = formatError
			return null
		}

		return value.longValue()
			.takeIf { it > 0 }
			?: run {
				errors[field] = formatError
				null
			}
	}

	private fun parseInstant(
		field: String,
		value: JsonNode?,
		requiredError: String,
		formatError: String,
		errors: MutableMap<String, String>,
	): Instant? {
		if (value == null || value.isNull) {
			errors[field] = requiredError
			return null
		}
		if (!value.isString) {
			errors[field] = formatError
			return null
		}

		return try {
			OffsetDateTime.parse(value.stringValue(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
		} catch (_: DateTimeParseException) {
			errors[field] = formatError
			null
		}
	}

	companion object {
		private const val SELLER_ID_REQUIRED_ERROR = "판매자 ID는 필수입니다."
		private const val SELLER_ID_FORMAT_ERROR = "판매자 ID는 1 이상의 정수여야 합니다."
		private const val PRODUCT_ID_FORMAT_ERROR = "상품 ID는 1 이상의 정수여야 합니다."
		private const val PRICE_REQUIRED_ERROR = "판매 가격은 필수입니다."
		private const val PRICE_FORMAT_ERROR = "판매 가격은 1 이상의 정수여야 합니다."
		private const val QUANTITY_REQUIRED_ERROR = "판매 수량은 필수입니다."
		private const val QUANTITY_FORMAT_ERROR = "판매 수량은 1 이상의 정수여야 합니다."
		private const val STARTS_AT_REQUIRED_ERROR = "판매 시작 시각은 필수입니다."
		private const val STARTS_AT_FORMAT_ERROR = "판매 시작 시각은 offset을 포함한 ISO-8601 형식이어야 합니다."
		private const val ENDS_AT_REQUIRED_ERROR = "판매 종료 시각은 필수입니다."
		private const val ENDS_AT_FORMAT_ERROR = "판매 종료 시각은 offset을 포함한 ISO-8601 형식이어야 합니다."
	}
}
