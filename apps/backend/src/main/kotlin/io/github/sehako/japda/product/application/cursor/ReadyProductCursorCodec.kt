package io.github.sehako.japda.product.application.cursor

import io.github.sehako.japda.product.domain.repository.ReadyProductCursorBoundary
import io.github.sehako.japda.product.domain.repository.ReadyProductSort
import io.github.sehako.japda.product.domain.repository.ReadyProductSummary
import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import java.util.Base64
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class ReadyProductCursorCodec(
	private val objectMapper: ObjectMapper,
) {
	fun encode(sort: ReadyProductSort, product: ReadyProductSummary): String {
		val payload = objectMapper.createObjectNode()
			.put("version", CURSOR_VERSION)
			.put("sort", sort.externalName)
			.put("id", product.id)
		if (sort.usesName) payload.put("name", product.name)

		return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(payload))
	}

	fun decode(cursor: String, requestedSort: ReadyProductSort): ReadyProductCursorBoundary = try {
		val payload = objectMapper.readTree(Base64.getUrlDecoder().decode(cursor))
		validateCommonPayload(payload, requestedSort)
		val id = payload.requiredPositiveLong("id")
		if (requestedSort.usesName) {
			ReadyProductCursorBoundary.Name(payload.requiredName(), id)
		} else {
			ReadyProductCursorBoundary.Id(id)
		}
	} catch (exception: ProductException) {
		throw exception
	} catch (exception: Exception) {
		throw ProductException(ProductErrorCode.CURSOR_INVALID)
	}

	private fun validateCommonPayload(payload: JsonNode, requestedSort: ReadyProductSort) {
		if (!payload.isObject) invalidCursor()
		val version = payload.get("version")
		if (version == null || !version.isIntegralNumber || !version.canConvertToInt() || version.intValue() != CURSOR_VERSION) invalidCursor()
		val sort = payload.get("sort")
		if (sort == null || !sort.isString || sort.asString() != requestedSort.externalName) invalidCursor()
	}

	private fun JsonNode.requiredPositiveLong(field: String): Long {
		val value = get(field)
		if (value == null || !value.isIntegralNumber || !value.canConvertToLong()) invalidCursor()
		return value.longValue().also { if (it <= 0) invalidCursor() }
	}

	private fun JsonNode.requiredName(): String {
		val value = get("name")
		if (value == null || !value.isString || value.asString().isBlank() || value.asString().length > MAX_PRODUCT_NAME_LENGTH) invalidCursor()
		return value.asString()
	}

	private fun invalidCursor(): Nothing = throw ProductException(ProductErrorCode.CURSOR_INVALID)

	private val ReadyProductSort.externalName: String
		get() = when (this) {
			ReadyProductSort.LATEST -> "latest"
			ReadyProductSort.OLDEST -> "oldest"
			ReadyProductSort.NAME_ASC -> "name-asc"
			ReadyProductSort.NAME_DESC -> "name-desc"
		}

	private val ReadyProductSort.usesName: Boolean
		get() = this == ReadyProductSort.NAME_ASC || this == ReadyProductSort.NAME_DESC

	private companion object {
		const val CURSOR_VERSION = 1
		const val MAX_PRODUCT_NAME_LENGTH = 100
	}
}
