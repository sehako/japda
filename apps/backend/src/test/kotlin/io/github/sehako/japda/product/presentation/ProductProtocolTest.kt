package io.github.sehako.japda.product.presentation

import io.github.sehako.japda.PostgreSqlTestContainerConfiguration
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgreSqlTestContainerConfiguration::class)
@DisplayName("상품 API 프로토콜")
class ProductProtocolTest(
	@Autowired private val mockMvc: MockMvc,
) {

	@Test
	@DisplayName("상품 API_지원하지 않는 메서드와 미디어 타입 상태를 ProblemDetail로 보존한다")
	fun 상품_API_지원하지_않는_메서드와_미디어_타입_상태를_ProblemDetail로_보존한다() {
		mockMvc.get("/api/products").andExpect {
			status { isMethodNotAllowed() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.status") { value(405) }
		}
		mockMvc.post("/api/products") {
			header("X-Seller-Id", "123")
			contentType = MediaType.TEXT_PLAIN
			content = "상품"
		}.andExpect {
			status { isUnsupportedMediaType() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.status") { value(415) }
		}
		mockMvc.post("/api/products/1/images") {
			header("X-Seller-Id", "123")
			contentType = MediaType.APPLICATION_JSON
			content = "{}"
		}.andExpect {
			status { isUnsupportedMediaType() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.status") { value(415) }
		}
	}
}
