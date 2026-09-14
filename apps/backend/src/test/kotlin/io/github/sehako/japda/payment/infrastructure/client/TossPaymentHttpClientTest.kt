package io.github.sehako.japda.payment.infrastructure.client

import com.sun.net.httpserver.HttpServer
import io.github.sehako.japda.payment.application.client.TossPaymentResult
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import tools.jackson.databind.json.JsonMapper

@DisplayName("토스 결제 HTTP Client")
class TossPaymentHttpClientTest {
	private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

	@AfterEach
	fun 종료() = server.stop(0)

	@Test
	@DisplayName("승인 요청은 Basic 인증과 저장된 멱등키 및 금액을 전송한다")
	fun 승인_요청_Basic_인증과_저장된_멱등키_및_금액을_전송한다() {
		var authorization: String? = null
		var idempotencyKey: String? = null
		var body: String? = null
		server.createContext("/v1/payments/confirm") { exchange ->
			authorization = exchange.requestHeaders.getFirst("Authorization")
			idempotencyKey = exchange.requestHeaders.getFirst("Idempotency-Key")
			body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
			val response = """{"paymentKey":"key","orderId":"order-123","totalAmount":70000,"status":"DONE","approvedAt":"2026-09-13T15:00:00+09:00"}"""
			exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
			exchange.responseBody.use { it.write(response.toByteArray()) }
		}
		server.start()

		val result = client().confirm("key", "order-123", 70_000L, "uuid-1")

		assertEquals("Basic " + Base64.getEncoder().encodeToString("test-secret:".toByteArray()), authorization)
		assertEquals("uuid-1", idempotencyKey)
		assertEquals("""{"paymentKey":"key","orderId":"order-123","amount":70000}""", body)
		assertEquals("DONE", assertIs<TossPaymentResult.Record>(result).status)
	}

	@Test
	@DisplayName("확정된 카드 거절은 승인 실패로 분류한다")
	fun 확정된_카드_거절_승인_실패로_분류한다() {
		server.createContext("/v1/payments/confirm") { exchange ->
			val response = """{"code":"REJECT_CARD_PAYMENT","message":"거절"}""".toByteArray()
			exchange.sendResponseHeaders(403, response.size.toLong())
			exchange.responseBody.use { it.write(response) }
		}
		server.start()

		assertEquals(TossPaymentResult.ConfirmedFailure, client().confirm("key", "order-123", 70_000L, "uuid-1"))
	}

	@Test
	@DisplayName("멱등 요청 처리 중 오류는 결제 실패로 단정하지 않는다")
	fun 멱등_요청_처리_중_오류_결제_실패로_단정하지_않는다() {
		server.createContext("/v1/payments/confirm") { exchange ->
			val response = """{"code":"IDEMPOTENT_REQUEST_PROCESSING","message":"처리 중"}""".toByteArray()
			exchange.sendResponseHeaders(409, response.size.toLong())
			exchange.responseBody.use { it.write(response) }
		}
		server.start()

		assertEquals(TossPaymentResult.Unavailable, client().confirm("key", "order-123", 70_000L, "uuid-1"))
	}

	@Test
	@DisplayName("결제 조회의 미발견 응답은 재승인 판단을 위해 별도로 분류한다")
	fun 결제_조회_미발견_응답을_분류한다() {
		server.createContext("/v1/payments/key") { exchange ->
			val response = """{"code":"NOT_FOUND_PAYMENT","message":"없음"}""".toByteArray()
			exchange.sendResponseHeaders(404, response.size.toLong())
			exchange.responseBody.use { it.write(response) }
		}
		server.start()

		assertEquals(TossPaymentResult.NotFound, client().lookup("key"))
	}

	private fun client() = TossPaymentHttpClient(
		"http://127.0.0.1:${server.address.port}", "test-secret", Duration.ofSeconds(3), Duration.ofSeconds(10), JsonMapper.builder().build(),
	)
}
