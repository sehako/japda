package io.github.sehako.japda.product.presentation.controller

import io.github.sehako.japda.auth.application.service.PrincipalIdentityService
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.exception.BusinessException
import io.github.sehako.japda.global.error.GlobalExceptionHandler
import io.github.sehako.japda.global.error.ProblemDetailFactory
import io.github.sehako.japda.product.application.cursor.ReadyProductCursorCodec
import io.github.sehako.japda.product.application.service.ProductService
import io.github.sehako.japda.product.domain.model.Product
import io.github.sehako.japda.product.domain.repository.ProductRepository
import io.github.sehako.japda.product.domain.repository.ReadyProductQuery
import io.github.sehako.japda.product.domain.repository.ReadyProductSort
import io.github.sehako.japda.product.domain.repository.ReadyProductSummary
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.mockito.Mockito.mock
import org.mockito.Mockito.doThrow
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
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
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.MockMvcConfigurer
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import tools.jackson.databind.json.JsonMapper

@DisplayName("상품 API")
@ExtendWith(RestDocumentationExtension::class)
class ProductControllerTest {
	private lateinit var mockMvc: MockMvc
	private lateinit var repository: IdAssigningProductRepository
	private lateinit var principalIdentityService: PrincipalIdentityService

	@BeforeEach
	fun setUp(restDocumentation: RestDocumentationContextProvider) {
		SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(17L, null)
		principalIdentityService = mock(PrincipalIdentityService::class.java) { invocation ->
			if (invocation.method.name == "sellerId") 1L else null
		}
		repository = IdAssigningProductRepository(1L)
		val service = ProductService(
			repository,
			Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC),
			ReadyProductCursorCodec(JsonMapper.builder().build()),
		)
		val restDocsConfigurer: MockMvcConfigurer = documentationConfiguration(restDocumentation)
		mockMvc = MockMvcBuilders
			.standaloneSetup(ProductController(service, principalIdentityService))
			.setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
			.setControllerAdvice(GlobalExceptionHandler(ProblemDetailFactory()))
			.apply<StandaloneMockMvcBuilder>(restDocsConfigurer)
			.build()
	}

	@AfterEach
	fun 인증_주체를_초기화한다() {
		SecurityContextHolder.clearContext()
	}

	@Test
	@DisplayName("판매자 헤더가 없어도 인증 주체의 연결로 READY 상품을 조회한다")
	fun 판매자_헤더_누락_인증_주체의_상품을_조회한다() {
		mockMvc.perform(get("/api/products/ready"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items").isArray)
		assertEquals(ReadyProductQuery(1L, ReadyProductSort.LATEST, null, 21), repository.readyQuery)
	}

	@Test
	@DisplayName("위조한 판매자 헤더는 READY 목록의 소유자를 바꾸지 못한다")
	fun READY_목록_판매자_헤더_위조_연결된_판매자로_조회한다() {
		mockMvc.perform(get("/api/products/ready").header("X-Seller-Id", "999"))
			.andExpect(status().isOk)
		assertEquals(ReadyProductQuery(1L, ReadyProductSort.LATEST, null, 21), repository.readyQuery)
	}

	@Test
	@DisplayName("쿼리 매개변수를 생략하면 기본값으로 READY 상품 목록을 반환한다")
	fun 쿼리_매개변수를_생략_기본값으로_READY_상품_목록을_반환한다() {
		repository.readyProducts = listOf(
			ReadyProductSummary(41L, "한정판 상품"),
			ReadyProductSummary(37L, "콜라보 상품"),
		)

		mockMvc.perform(get("/api/products/ready"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items[0].id").value(41))
			.andExpect(jsonPath("$.items[0].name").value("한정판 상품"))
			.andExpect(jsonPath("$.items[1].id").value(37))
			.andExpect(jsonPath("$.items[1].name").value("콜라보 상품"))
			.andExpect(jsonPath("$.nextCursor").value(null))

		assertEquals(ReadyProductQuery(1L, ReadyProductSort.LATEST, null, 21), repository.readyQuery)
	}

	@Test
	@DisplayName("정렬과 커서와 크기를 전달하면 다음 페이지와 커서를 반환하고 계약을 문서화한다")
	fun 정렬_커서_크기를_전달_다음_페이지와_커서를_반환하고_문서화한다() {
		val codec = ReadyProductCursorCodec(JsonMapper.builder().build())
		val cursor = codec.encode(ReadyProductSort.NAME_ASC, ReadyProductSummary(20L, "가 상품"))
		repository.readyProducts = listOf(
			ReadyProductSummary(21L, "나 상품"),
			ReadyProductSummary(22L, "다 상품"),
		)

		mockMvc.perform(
			get("/api/products/ready")
				.header("Cookie", "JAPDA_ACCESS_TOKEN=<JWT>")
				.queryParam("sort", "name-asc")
				.queryParam("cursor", cursor)
				.queryParam("size", "1"),
		)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(21))
			.andExpect(jsonPath("$.nextCursor").isString)
			.andDo(
				document(
					"product-ready-list",
					preprocessRequest(prettyPrint()),
					preprocessResponse(prettyPrint()),
					requestHeaders(headerWithName("Cookie").description("JAPDA_ACCESS_TOKEN 인증 쿠키")),
					queryParameters(
						parameterWithName("sort").description("정렬 방식: latest, oldest, name-asc, name-desc").optional(),
						parameterWithName("cursor").description("직전 응답에서 받은 불투명 커서").optional(),
						parameterWithName("size").description("페이지 크기(1~100, 기본값 20)").optional(),
					),
					responseFields(
						fieldWithPath("items").description("판매 준비 완료 상품 목록"),
						fieldWithPath("items[].id").description("상품 식별자"),
						fieldWithPath("items[].name").description("상품명"),
						fieldWithPath("nextCursor").description("다음 페이지 커서, 마지막 페이지이면 null"),
					),
				),
			)
	}

	@Test
	@DisplayName("인증 주체가 없으면 READY 목록에 인증 실패를 반환한다")
	fun READY_목록_인증_주체_누락_인증_실패를_반환한다() {
		SecurityContextHolder.clearContext()
		mockMvc.perform(get("/api/products/ready").header("X-Seller-Id", "1"))
			.andExpect(status().isUnauthorized)
			.andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
	}

	@Test
	@DisplayName("판매자 연결이 없으면 READY 목록에 연결 필요 오류를 반환하고 문서화한다")
	fun READY_목록_판매자_연결_부재_연결_필요_오류를_반환한다() {
		doThrow(BusinessException(AuthErrorCode.SELLER_LINK_REQUIRED))
			.`when`(principalIdentityService).sellerId(17L)
		mockMvc.perform(get("/api/products/ready"))
			.andExpect(status().isForbidden)
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.code").value("AUTH_SELLER_LINK_REQUIRED"))
			.andDo(document(
				"product-ready-list-seller-link-required",
				preprocessResponse(prettyPrint()),
				responseHeaders(
					headerWithName("Cache-Control").description("민감한 오류 응답의 저장 방지"),
				),
				responseFields(
					fieldWithPath("type").description("오류 유형 URI"),
					fieldWithPath("title").description("오류 제목"),
					fieldWithPath("status").description("HTTP 상태 코드"),
					fieldWithPath("detail").description("오류 설명"),
					fieldWithPath("instance").description("오류가 발생한 요청 경로"),
					fieldWithPath("code").description("안정적인 오류 코드"),
				),
			))
	}

	@Test
	@DisplayName("페이지 크기가 Int 형식이 아니면 공통 쿼리 매개변수 ProblemDetail을 반환한다")
	fun READY_목록_페이지_크기가_Int_형식이_아님_공통_매개변수_오류를_반환한다() {
		assertReadyInvalidRequest(size = "2147483648", expectedCode = "COMMON_REQUEST_PARAMETER_INVALID")
	}

	@Test
	@DisplayName("지원하지 않는 정렬이면 상품 정렬 ProblemDetail을 반환한다")
	fun READY_목록_지원하지_않는_정렬_상품_정렬_오류를_반환한다() {
		assertReadyInvalidRequest(sort = "newest", expectedCode = "PRODUCT_SORT_INVALID", expectedProperty = "sort")
			.andDo(
				document(
					"product-ready-list-sort-invalid",
					preprocessRequest(prettyPrint()),
					preprocessResponse(prettyPrint()),
					responseFields(
						fieldWithPath("type").description("오류 유형 URI"),
						fieldWithPath("title").description("오류 제목"),
						fieldWithPath("status").description("HTTP 상태 코드"),
						fieldWithPath("detail").description("오류 설명"),
						fieldWithPath("instance").description("오류가 발생한 요청 경로"),
						fieldWithPath("code").description("안정적인 오류 코드"),
						fieldWithPath("errors.sort").description("정렬 방식 오류 메시지"),
					),
				),
			)
	}

	@Test
	@DisplayName("페이지 크기가 허용 범위 밖이면 상품 페이지 크기 ProblemDetail을 반환한다")
	fun READY_목록_페이지_크기가_범위_밖_상품_페이지_크기_오류를_반환한다() {
		assertReadyInvalidRequest(size = "101", expectedCode = "PRODUCT_PAGE_SIZE_INVALID", expectedProperty = "size")
	}

	private fun assertReadyInvalidRequest(
		sort: String? = null,
		size: String? = null,
		expectedCode: String,
		expectedProperty: String? = null,
	): ResultActions {
		val request = get("/api/products/ready")
		if (sort != null) request.queryParam("sort", sort)
		if (size != null) request.queryParam("size", size)

		val result = mockMvc.perform(request)
			.andExpect(status().isBadRequest)
			.andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
			.andExpect(jsonPath("$.instance").value("/api/products/ready"))
			.andExpect(jsonPath("$.code").value(expectedCode))
		if (expectedProperty != null) result.andExpect(jsonPath("$.errors.$expectedProperty").exists())
		return result
	}

	@Test
	@DisplayName("유효한 요청이면 상품을 생성하고 201 응답을 반환한다")
	fun 유효한_요청_상품을_생성하고_201을_반환한다() {
		mockMvc.perform(
			post("/api/products")
				.header("Cookie", "JAPDA_ACCESS_TOKEN=<JWT>")
				.header("X-CSRF-TOKEN", "<CSRF 토큰>")
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
						headerWithName("Cookie").description("JAPDA_ACCESS_TOKEN 인증 쿠키"),
						headerWithName("X-CSRF-TOKEN").description("GET /api/auth/csrf에서 받은 CSRF 토큰"),
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
	@DisplayName("위조한 판매자 헤더는 상품 소유자를 바꾸지 못한다")
	fun 판매자_헤더_위조_연결된_판매자로_상품을_생성한다() {
		mockMvc.perform(
			post("/api/products")
				.header("X-Seller-Id", "999")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"name":"상품"}"""),
		)
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.sellerId").value(1))
	}

	@Test
	@DisplayName("설명이 공백이면 응답에 null 설명을 포함한다")
	fun 설명이_공백_응답에_null_설명을_포함한다() {
		mockMvc.perform(
			post("/api/products")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"name":"상품","description":"   "}"""),
		)
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.description").value(null))
	}

	@Test
	@DisplayName("인증 주체가 없으면 상품 등록에 인증 실패를 반환한다")
	fun 상품_등록_인증_주체_누락_인증_실패를_반환한다() {
		SecurityContextHolder.clearContext()
		mockMvc.perform(
			post("/api/products")
				.header("X-Seller-Id", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"name":"상품"}"""),
		)
			.andExpect(status().isUnauthorized)
			.andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
	}

	@Test
	@DisplayName("상품명이 누락되면 상품명 필수 오류를 반환한다")
	fun 상품명이_누락_상품명_필수_오류를_반환한다() {
		assertInvalidRequest(
			requestBody = "{}",
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
			override fun findById(id: Long): Product? = null
			override fun findByIdForUpdate(id: Long): Product? = null
			override fun findReadyProducts(query: ReadyProductQuery): List<ReadyProductSummary> = emptyList()
		}
		val service = ProductService(
			failingRepository,
			Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC),
			ReadyProductCursorCodec(JsonMapper.builder().build()),
		)
		val failingMockMvc = MockMvcBuilders
			.standaloneSetup(ProductController(service, principalIdentityService))
			.setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
			.setControllerAdvice(GlobalExceptionHandler(ProblemDetailFactory()))
			.build()

		failingMockMvc.perform(
			post("/api/products")
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
		expectedCode: String,
		expectedProperty: String? = null,
		expectedMessage: String? = null,
	): ResultActions {
		val request = post("/api/products")
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestBody)

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
		var readyProducts: List<ReadyProductSummary> = emptyList()
		var readyQuery: ReadyProductQuery? = null

		override fun save(product: Product): Product {
			Product::class.java.getDeclaredField("id").apply {
				isAccessible = true
				set(product, generatedId)
			}
			return product
		}

		override fun findById(id: Long): Product? = null
		override fun findByIdForUpdate(id: Long): Product? = null
		override fun findReadyProducts(query: ReadyProductQuery): List<ReadyProductSummary> {
			readyQuery = query
			return readyProducts
		}
	}
}
