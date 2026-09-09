package io.github.sehako.japda.product.presentation.image

import io.github.sehako.japda.PostgreSqlTestContainerConfiguration
import io.github.sehako.japda.product.application.image.ProductImageService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
	webEnvironment = RANDOM_PORT,
	properties = [
		"product.image.s3.bucket=test-private-bucket",
		"product.image.s3.region=ap-northeast-2",
	],
)
@Import(PostgreSqlTestContainerConfiguration::class)
@DisplayName("상품 이미지 multipart 용량 제한 통합")
class ProductImageMultipartLimitIntegrationTest(
	@LocalServerPort private val port: Int,
	@Autowired private val objectMapper: ObjectMapper,
) {
	@MockitoBean
	private lateinit var productImageService: ProductImageService

	@Test
	@DisplayName("개별 파일 용량 초과_Controller 진입 전에 413 ProblemDetail로 거부한다")
	fun 개별_파일_용량_초과_Controller_진입_전에_413_ProblemDetail로_거부한다() {
		val boundary = "japda-multipart-limit-boundary"
		val requestPath = "/api/products/1/images"
		val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$requestPath"))
			.header("X-Seller-Id", "1")
			.header("Content-Type", "multipart/form-data; boundary=$boundary")
			.POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary)))
			.build()

		val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
		val problem = objectMapper.readTree(response.body())

		assertEquals(413, response.statusCode())
		assertTrue(
			response.headers().firstValue("Content-Type").orElse("")
				.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE),
		)
		assertEquals(requestPath, problem.path("instance").stringValue())
		assertTrue(problem.path("errors").has("files"), response.body())
		assertEquals(
			setOf("type", "title", "status", "detail", "instance", "errors"),
			problem.properties().map { it.key }.toSet(),
		)
	}

	private fun multipartBody(boundary: String): ByteArray {
		val prefix = buildString {
			append("--$boundary\r\n")
			append("Content-Disposition: form-data; name=\"files\"; filename=\"oversized.jpg\"\r\n")
			append("Content-Type: image/jpeg\r\n\r\n")
		}.toByteArray(StandardCharsets.US_ASCII)
		val suffix = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.US_ASCII)
		val body = ByteArray(prefix.size + OVERSIZED_FILE_BYTES + suffix.size)

		prefix.copyInto(body)
		body[prefix.size] = 0xFF.toByte()
		body[prefix.size + 1] = 0xD8.toByte()
		suffix.copyInto(body, prefix.size + OVERSIZED_FILE_BYTES)
		return body
	}

	companion object {
		private const val MAX_FILE_BYTES = 10 * 1024 * 1024
		private const val OVERSIZED_FILE_BYTES = MAX_FILE_BYTES + 1
	}
}
