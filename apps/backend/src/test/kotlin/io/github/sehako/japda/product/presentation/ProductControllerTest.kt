package io.github.sehako.japda.product.presentation

import io.github.sehako.japda.global.error.GlobalExceptionHandler
import io.github.sehako.japda.global.error.ProblemDetailFactory
import io.github.sehako.japda.product.application.ProductService
import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.http.MediaType
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest
import org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse
import org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.MockMvcConfigurer
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder

@DisplayName("상품 등록 API")
@ExtendWith(RestDocumentationExtension::class)
class ProductControllerTest {
	private lateinit var mockMvc: MockMvc

	@BeforeEach
	fun setUp(restDocumentation: RestDocumentationContextProvider) {
		val repository = IdAssigningProductRepository(1L)
		val service = ProductService(
			repository,
			Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC),
		)
		val restDocsConfigurer: MockMvcConfigurer = documentationConfiguration(restDocumentation)
		mockMvc = MockMvcBuilders
			.standaloneSetup(ProductController(service))
			.setControllerAdvice(GlobalExceptionHandler(ProblemDetailFactory()))
			.apply<StandaloneMockMvcBuilder>(restDocsConfigurer)
			.build()
	}

	@Test
	@DisplayName("유효한 요청이면 상품을 생성하고 201 응답을 반환한다")
	fun 유효한_요청_상품을_생성하고_201을_반환한다() {
		mockMvc.perform(
			post("/api/products")
				.header("X-Seller-Id", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"name":"  한정판 상품  ","description":"  선택적인 상품 설명  "}"""),
		)
			.andExpect(status().isCreated)
			.andExpect(header().string("Location", "/api/products/1"))
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.sellerId").value(1))
			.andExpect(jsonPath("$.name").value("한정판 상품"))
			.andExpect(jsonPath("$.description").value("선택적인 상품 설명"))
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.createdAt").value("2026-09-10T00:00:00Z"))
			.andDo(
				document(
					"product-create",
					preprocessRequest(prettyPrint()),
					preprocessResponse(prettyPrint()),
					requestHeaders(
						headerWithName("X-Seller-Id").description("임시 판매자 식별자"),
					),
					requestFields(
						fieldWithPath("name").description("상품명"),
						fieldWithPath("description").description("선택적인 상품 설명").optional(),
					),
					responseHeaders(
						headerWithName("Location").description("생성된 상품 리소스 경로"),
					),
					responseFields(
						fieldWithPath("id").description("상품 식별자"),
						fieldWithPath("sellerId").description("판매자 식별자"),
						fieldWithPath("name").description("정규화된 상품명"),
						fieldWithPath("description").description("정규화된 상품 설명").optional(),
						fieldWithPath("status").description("상품 상태"),
						fieldWithPath("createdAt").description("상품 생성 시각"),
					),
				),
			)
	}

	@Test
	@DisplayName("설명이 공백이면 응답에 null 설명을 포함한다")
	fun 설명이_공백_응답에_null_설명을_포함한다() {
		mockMvc.perform(
			post("/api/products")
				.header("X-Seller-Id", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"name":"상품","description":"   "}"""),
		)
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.description").value(null))
	}

	@Test
	@DisplayName("판매자 헤더가 없으면 ProblemDetail을 반환한다")
	fun 판매자_헤더가_없음_헤더_누락_오류를_반환한다() {
		assertInvalidRequest(
			requestBody = """{"name":"상품"}""",
			sellerId = null,
			expectedCode = "COMMON_REQUEST_HEADER_MISSING",
		)
	}

	@Test
	@DisplayName("판매자 헤더가 숫자가 아니면 ProblemDetail을 반환한다")
	fun 판매자_헤더가_숫자가_아님_헤더_형식_오류를_반환한다() {
		assertInvalidRequest(
			requestBody = """{"name":"상품"}""",
			sellerId = "seller",
			expectedCode = "COMMON_REQUEST_HEADER_INVALID",
		)
	}

	@Test
	@DisplayName("판매자 식별자가 양수가 아니면 상품 오류를 반환한다")
	fun 판매자_식별자가_양수가_아님_상품_오류를_반환한다() {
		assertInvalidRequest(
			requestBody = """{"name":"상품"}""",
			sellerId = "0",
			expectedCode = "PRODUCT_SELLER_ID_INVALID",
			expectedProperty = "sellerId",
			expectedMessage = "판매자 식별자는 양수여야 합니다.",
		)
	}

	@Test
	@DisplayName("상품명이 누락되면 상품명 필수 오류를 반환한다")
	fun 상품명이_누락_상품명_필수_오류를_반환한다() {
		assertInvalidRequest(
			requestBody = "{}",
			sellerId = "1",
			expectedCode = "PRODUCT_NAME_REQUIRED",
			expectedProperty = "name",
			expectedMessage = "상품명은 필수입니다.",
		)
			.andDo(
				document(
					"product-create-name-required",
					preprocessRequest(prettyPrint()),
					preprocessResponse(prettyPrint()),
					responseFields(
						fieldWithPath("type").description("오류 유형 URI"),
						fieldWithPath("title").description("오류 제목"),
						fieldWithPath("status").description("HTTP 상태 코드"),
						fieldWithPath("detail").description("오류 설명"),
						fieldWithPath("instance").description("오류가 발생한 요청 경로"),
						fieldWithPath("code").description("안정적인 오류 코드"),
						fieldWithPath("errors.name").description("상품명 오류 메시지"),
					),
				),
			)
	}

	@Test
	@DisplayName("상품명이 100자를 초과하면 상품명 길이 오류를 반환한다")
	fun 상품명이_100자를_초과_상품명_길이_오류를_반환한다() {
		assertInvalidRequest(
			requestBody = """{"name":"${"가".repeat(101)}"}""",
			sellerId = "1",
			expectedCode = "PRODUCT_NAME_TOO_LONG",
			expectedProperty = "name",
			expectedMessage = "상품명은 100자 이하여야 합니다.",
		)
	}

	@Test
	@DisplayName("상품 설명이 3000자를 초과하면 설명 길이 오류를 반환한다")
	fun 상품_설명이_3000자를_초과_설명_길이_오류를_반환한다() {
		assertInvalidRequest(
			requestBody = """{"name":"상품","description":"${"가".repeat(3001)}"}""",
			sellerId = "1",
			expectedCode = "PRODUCT_DESCRIPTION_TOO_LONG",
			expectedProperty = "description",
			expectedMessage = "상품 설명은 3000자 이하여야 합니다.",
		)
	}

	@Test
	@DisplayName("요청 JSON을 파싱할 수 없으면 공통 본문 오류를 반환한다")
	fun 요청_JSON을_파싱할_수_없음_공통_본문_오류를_반환한다() {
		assertInvalidRequest(
			requestBody = """{"name":}""",
			sellerId = "1",
			expectedCode = "COMMON_REQUEST_BODY_MALFORMED",
		)
	}

	@Test
	@DisplayName("예상하지 못한 저장 실패면 내부 정보를 숨긴 서버 오류를 반환한다")
	fun 예상하지_못한_저장_실패_내부_정보를_숨긴_서버_오류를_반환한다() {
		val failingRepository = object : ProductRepository {
			override fun save(product: Product): Product {
				throw IllegalStateException("민감한 SQL 오류")
			}
		}
		val service = ProductService(
			failingRepository,
			Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC),
		)
		val failingMockMvc = MockMvcBuilders
			.standaloneSetup(ProductController(service))
			.setControllerAdvice(GlobalExceptionHandler(ProblemDetailFactory()))
			.build()

		failingMockMvc.perform(
			post("/api/products")
				.header("X-Seller-Id", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"name":"상품"}"""),
		)
			.andExpect(status().isInternalServerError)
			.andExpect(jsonPath("$.code").value("COMMON_INTERNAL_SERVER_ERROR"))
			.andExpect(jsonPath("$.detail").value("서버 내부 오류가 발생했습니다."))
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("민감한 SQL 오류"))))
	}

	private fun assertInvalidRequest(
		requestBody: String,
		sellerId: String?,
		expectedCode: String,
		expectedProperty: String? = null,
		expectedMessage: String? = null,
	): ResultActions {
		val request = post("/api/products")
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestBody)
		if (sellerId != null) {
			request.header("X-Seller-Id", sellerId)
		}

		val result = mockMvc.perform(request)
			.andExpect(status().isBadRequest)
			.andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
			.andExpect(jsonPath("$.type").value("about:blank"))
			.andExpect(jsonPath("$.title").value("잘못된 요청"))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.detail").value("요청 값이 올바르지 않습니다."))
			.andExpect(jsonPath("$.instance").value("/api/products"))
			.andExpect(jsonPath("$.code").value(expectedCode))

		if (expectedProperty != null && expectedMessage != null) {
			result.andExpect(jsonPath("$.errors.$expectedProperty").value(expectedMessage))
		}
		return result
	}

	private class IdAssigningProductRepository(
		private val generatedId: Long,
	) : ProductRepository {
		override fun save(product: Product): Product {
			Product::class.java.getDeclaredField("id").apply {
				isAccessible = true
				set(product, generatedId)
			}
			return product
		}
	}
}
