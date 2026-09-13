package io.github.sehako.japda.payment.infrastructure.client

import io.github.sehako.japda.payment.application.client.TossPaymentClient
import io.github.sehako.japda.payment.application.client.TossPaymentResult
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class TossPaymentHttpClient(
	@Value("\${payment.toss.base-url:https://api.tosspayments.com}") private val baseUrl: String,
	@Value("\${payment.toss.secret-key:}") private val secretKey: String,
	@Value("\${payment.toss.connect-timeout:PT3S}") connectTimeout: Duration,
	@Value("\${payment.toss.response-timeout:PT10S}") private val responseTimeout: Duration,
	private val mapper: ObjectMapper,
) : TossPaymentClient {
	private val client = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

	override fun confirm(paymentKey: String, orderId: String, amount: Long, idempotencyKey: String): TossPaymentResult {
		val body = mapper.createObjectNode()
			.put("paymentKey", paymentKey)
			.put("orderId", orderId)
			.put("amount", amount)
		val request = request("/v1/payments/confirm")
			.header("Content-Type", "application/json")
			.header("Idempotency-Key", idempotencyKey)
			.POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
			.build()
		return send(request, lookup = false)
	}

	override fun lookup(paymentKey: String): TossPaymentResult {
		val encoded = URLEncoder.encode(paymentKey, StandardCharsets.UTF_8).replace("+", "%20")
		return send(request("/v1/payments/$encoded").GET().build(), lookup = true)
	}

	private fun request(path: String): HttpRequest.Builder {
		val basic = Base64.getEncoder().encodeToString("$secretKey:".toByteArray(StandardCharsets.UTF_8))
		return HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
			.timeout(responseTimeout)
			.header("Authorization", "Basic $basic")
			.header("Accept", "application/json")
	}

	private fun send(request: HttpRequest, lookup: Boolean): TossPaymentResult = try {
		val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
		if (response.statusCode() in 200..299) {
			parseRecord(response.body())
		} else {
			val code = try { mapper.readTree(response.body()).get("code")?.asString() } catch (_: Exception) { null }
			when {
				lookup && response.statusCode() == 404 && code == "NOT_FOUND_PAYMENT" -> TossPaymentResult.NotFound
				!lookup && code in CONFIRMED_FAILURE_CODES -> TossPaymentResult.ConfirmedFailure
				else -> TossPaymentResult.Unavailable
			}
		}
	} catch (_: InterruptedException) {
		Thread.currentThread().interrupt()
		TossPaymentResult.Unavailable
	} catch (_: Exception) {
		TossPaymentResult.Unavailable
	}

	private fun parseRecord(body: String): TossPaymentResult = try {
		val json = mapper.readTree(body)
		val paymentKey = json.requiredString("paymentKey")
		val orderId = json.requiredString("orderId")
		val amountNode = json.get("totalAmount")
		val status = json.requiredString("status")
		if (amountNode == null || !amountNode.isIntegralNumber || !amountNode.canConvertToLong()) {
			TossPaymentResult.InvalidData
		} else {
			val approvedAtNode = json.get("approvedAt")
			val approvedAt = approvedAtNode?.takeIf { it.isString }?.asString()?.let { OffsetDateTime.parse(it).toInstant() }
			TossPaymentResult.Record(paymentKey, orderId, amountNode.longValue(), status, approvedAt)
		}
	} catch (_: Exception) {
		TossPaymentResult.InvalidData
	}

	private fun JsonNode.requiredString(name: String): String =
		get(name)?.takeIf { it.isString }?.asString() ?: throw IllegalArgumentException(name)

	private companion object {
		val CONFIRMED_FAILURE_CODES = setOf(
			"REJECT_ACCOUNT_PAYMENT", "REJECT_CARD_PAYMENT", "REJECT_CARD_COMPANY", "NOT_FOUND_PAYMENT_SESSION",
			"INVALID_REJECT_CARD", "INVALID_STOPPED_CARD", "INVALID_CARD_LOST_OR_STOLEN", "FDS_ERROR",
		)
	}
}
