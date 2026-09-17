package io.github.sehako.japda.order.application.cursor

import io.github.sehako.japda.order.domain.repository.BuyerOrderCursorBoundary
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import java.time.Instant
import java.util.Base64
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class OrderCursorCodec(
	private val objectMapper: ObjectMapper,
) {
	fun encode(boundary: BuyerOrderCursorBoundary): String {
		val payload = objectMapper.createObjectNode()
			.put("version", CURSOR_VERSION)
			.put("createdAt", boundary.createdAt.toString())
			.put("orderId", boundary.orderId)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(payload))
	}

	fun decode(cursor: String): BuyerOrderCursorBoundary = try {
		val payload = objectMapper.readTree(Base64.getUrlDecoder().decode(cursor))
		validateVersion(payload)
		val createdAt = payload.requiredInstant("createdAt")
		val orderId = payload.requiredPositiveLong("orderId")
		BuyerOrderCursorBoundary(createdAt, orderId)
	} catch (exception: OrderException) {
		throw exception
	} catch (_: Exception) {
		throw OrderException(OrderErrorCode.CURSOR_INVALID)
	}

	private fun validateVersion(payload: JsonNode) {
		if (!payload.isObject) invalidCursor()
		val version = payload.get("version")
		if (version == null || !version.isIntegralNumber || !version.canConvertToInt() || version.intValue() != CURSOR_VERSION) {
			invalidCursor()
		}
	}

	private fun JsonNode.requiredInstant(field: String): Instant {
		val value = get(field)
		if (value == null || !value.isString) invalidCursor()
		return Instant.parse(value.asString())
	}

	private fun JsonNode.requiredPositiveLong(field: String): Long {
		val value = get(field)
		if (value == null || !value.isIntegralNumber || !value.canConvertToLong()) invalidCursor()
		return value.longValue().also { if (it <= 0) invalidCursor() }
	}

	private fun invalidCursor(): Nothing = throw OrderException(OrderErrorCode.CURSOR_INVALID)

	private companion object {
		const val CURSOR_VERSION = 1
	}
}
