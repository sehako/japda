package io.github.sehako.japda.global.config

import io.github.sehako.japda.auth.application.service.PrincipalIdentityService
import io.github.sehako.japda.global.error.ProblemDetailFactory
import io.github.sehako.japda.product.application.service.ProductService
import io.github.sehako.japda.product.presentation.controller.ProductController
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@DisplayName("CORS 설정")
@WebMvcTest(
	controllers = [ProductController::class],
	excludeAutoConfiguration = [OAuth2ClientWebSecurityAutoConfiguration::class],
	properties = [
		"cors.allowed-origins[0]=http://localhost:5173",
		"cors.allowed-origins[1]=https://frontend.example.com",
	],
)
@Import(ProblemDetailFactory::class)
class CorsConfigTest {
	@Autowired
	private lateinit var mockMvc: MockMvc

	@MockitoBean
	private lateinit var productService: ProductService

	@MockitoBean
	private lateinit var principalIdentityService: PrincipalIdentityService

	@Test
	@DisplayName("설정된 여러 origin의 API preflight 요청을 허용한다")
	fun 설정된_여러_origin_API_preflight_요청_허용한다() {
		listOf("http://localhost:5173", "https://frontend.example.com").forEach { origin ->
			mockMvc.perform(preflightRequest(origin))
				.andExpect(status().isOk)
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("X-CSRF-TOKEN")))
		}
	}

	@Test
	@DisplayName("허용된 origin의 API 요청에 자격 증명 CORS 응답을 제공한다")
	fun 허용된_origin_API_요청에_자격_증명_CORS_응답을_제공한다() {
		mockMvc.perform(
			get("/api/products/ready")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173"),
		)
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
	}

	@Test
	@DisplayName("설정되지 않은 origin의 API preflight 요청을 거부한다")
	fun 설정되지_않은_origin_API_preflight_요청_거부한다() {
		mockMvc.perform(preflightRequest("http://localhost:5174"))
			.andExpect(status().isForbidden)
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
	}

	@Test
	@DisplayName("API가 아닌 경로에는 CORS를 적용하지 않는다")
	fun API가_아닌_경로_CORS를_적용하지_않는다() {
		mockMvc.perform(
			get("/docs/index.html")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173"),
		)
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
	}

	private fun preflightRequest(origin: String) = options("/api/products")
		.header(HttpHeaders.ORIGIN, origin)
		.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
		.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-CSRF-TOKEN, Content-Type")
}
