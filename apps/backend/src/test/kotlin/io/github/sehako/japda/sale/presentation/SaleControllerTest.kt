package io.github.sehako.japda.sale.presentation

import io.github.sehako.japda.global.error.GlobalExceptionHandler
import io.github.sehako.japda.global.error.ProblemDetailFactory
import io.github.sehako.japda.sale.application.BuyerSaleProductListResponse
import io.github.sehako.japda.sale.application.BuyerSaleProductResponse
import io.github.sehako.japda.sale.application.BuyerSaleStatus
import io.github.sehako.japda.sale.application.CreateSaleDto
import io.github.sehako.japda.sale.application.SaleResponse
import io.github.sehako.japda.sale.application.SaleService
import io.github.sehako.japda.sale.exception.SaleErrorCode
import io.github.sehako.japda.sale.exception.SaleException
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.mockmvc.RestDocumentationResultHandler
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.MockMvcConfigurer
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder

@DisplayName("판매 API")
@ExtendWith(RestDocumentationExtension::class)
class SaleControllerTest {
	private lateinit var mockMvc: MockMvc
	private lateinit var saleService: SaleService

	@BeforeEach
	fun setUp(restDocumentation: RestDocumentationContextProvider) {
		saleService = mock(SaleService::class.java)
		val restDocsConfigurer: MockMvcConfigurer = documentationConfiguration(restDocumentation)
		mockMvc = MockMvcBuilders
			.standaloneSetup(SaleController(saleService))
			.setControllerAdvice(GlobalExceptionHandler(ProblemDetailFactory()))
			.apply<StandaloneMockMvcBuilder>(restDocsConfigurer)
			.build()
	}

	@Test
	@DisplayName("유효한 요청이면 판매 일정을 생성하고 Location 없이 201 응답을 반환한다")
	fun 유효한_요청_판매_일정을_생성하고_Location_없이_201을_반환한다() {
		val expectedDto = CreateSaleDto(1L, 10L, LocalDate.parse("2026-09-12"), 35_000L, 100)
		`when`(saleService.create(expectedDto)).thenReturn(
			SaleResponse(
				id = 100L,
				productId = 10L,
				sellerId = 1L,
				saleDate = LocalDate.parse("2026-09-12"),
				price = 35_000L,
				quantity = 100,
				startsAt = Instant.parse("2026-09-11T15:00:00Z"),
				endsAt = Instant.parse("2026-09-12T15:00:00Z"),
				createdAt = Instant.parse("2026-09-11T00:00:01Z"),
			),
		)

		mockMvc.perform(
			post("/api/sales")
				.header("X-Seller-Id", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"productId":10,"saleDate":"2026-09-12","price":35000,"quantity":100}"""),
		)
			.andExpect(status().isCreated)
			.andExpect(header().doesNotExist("Location"))
			.andExpect(jsonPath("$.id").value(100))
			.andExpect(jsonPath("$.productId").value(10))
			.andExpect(jsonPath("$.sellerId").value(1))
			.andExpect(jsonPath("$.saleDate").value("2026-09-12"))
			.andExpect(jsonPath("$.price").value(35000))
			.andExpect(jsonPath("$.quantity").value(100))
			.andExpect(jsonPath("$.startsAt").value("2026-09-11T15:00:00Z"))
			.andExpect(jsonPath("$.endsAt").value("2026-09-12T15:00:00Z"))
			.andExpect(jsonPath("$.createdAt").value("2026-09-11T00:00:01Z"))
			.andDo(
				document(
					"sale-create",
					preprocessRequest(prettyPrint()),
					preprocessResponse(prettyPrint()),
					requestHeaders(headerWithName("X-Seller-Id").description("임시 판매자 식별자")),
					requestFields(
						fieldWithPath("productId").description("상품 식별자"),
						fieldWithPath("saleDate").description("Asia/Seoul 기준 판매일"),
						fieldWithPath("price").description("원화 기준 판매 가격"),
						fieldWithPath("quantity").description("판매 수량"),
					),
					responseFields(
						fieldWithPath("id").description("판매 일정 식별자"),
						fieldWithPath("productId").description("상품 식별자"),
						fieldWithPath("sellerId").description("판매자 식별자"),
						fieldWithPath("saleDate").description("Asia/Seoul 기준 판매일"),
						fieldWithPath("price").description("원화 기준 판매 가격"),
						fieldWithPath("quantity").description("판매 수량"),
						fieldWithPath("startsAt").description("판매 시작 시각"),
						fieldWithPath("endsAt").description("판매 종료 시각"),
						fieldWithPath("createdAt").description("판매 일정 생성 시각"),
					),
				),
			)

		verify(saleService).create(expectedDto)
	}

	@Test
	@DisplayName("판매일에 해당하는 구매자 판매 상품 목록을 공개 조회하고 문서화한다")
	fun 판매일에_해당하는_구매자_판매_상품_목록을_공개_조회하고_문서화한다() {
		val saleDate = LocalDate.parse("2026-09-10")
		`when`(saleService.findBuyerSaleProducts(saleDate)).thenReturn(
			BuyerSaleProductListResponse(
				sales = listOf(
					BuyerSaleProductResponse(
						saleId = 100L,
						productId = 42L,
						name = "한정판 상품",
						description = null,
						price = 35_000L,
						quantity = 100,
						saleDate = saleDate,
						startsAt = Instant.parse("2026-09-09T15:00:00Z"),
						endsAt = Instant.parse("2026-09-10T15:00:00Z"),
						status = BuyerSaleStatus.ON_SALE,
						representativeImagePath = "/products/42/request-id/object-id",
					),
				),
			),
		)

		mockMvc.perform(get("/api/sales").queryParam("saleDate", "2026-09-10"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.sales[0].saleId").value(100))
			.andExpect(jsonPath("$.sales[0].productId").value(42))
			.andExpect(jsonPath("$.sales[0].name").value("한정판 상품"))
			.andExpect(jsonPath("$.sales[0].description").value(null))
			.andExpect(jsonPath("$.sales[0].price").value(35000))
			.andExpect(jsonPath("$.sales[0].quantity").value(100))
			.andExpect(jsonPath("$.sales[0].saleDate").value("2026-09-10"))
			.andExpect(jsonPath("$.sales[0].startsAt").value("2026-09-09T15:00:00Z"))
			.andExpect(jsonPath("$.sales[0].endsAt").value("2026-09-10T15:00:00Z"))
			.andExpect(jsonPath("$.sales[0].status").value("ON_SALE"))
			.andExpect(jsonPath("$.sales[0].representativeImagePath").value("/products/42/request-id/object-id"))
			.andDo(
				document(
					"buyer-sale-product-list",
					preprocessRequest(prettyPrint()),
					preprocessResponse(prettyPrint()),
					queryParameters(parameterWithName("saleDate").description("Asia/Seoul 기준 조회 판매일(YYYY-MM-DD)")),
					responseFields(
						fieldWithPath("sales").description("판매 상품 목록"),
						fieldWithPath("sales[].saleId").description("판매 일정 식별자"),
						fieldWithPath("sales[].productId").description("상품 식별자"),
						fieldWithPath("sales[].name").description("상품명"),
						fieldWithPath("sales[].description").description("상품 설명").optional(),
						fieldWithPath("sales[].price").description("원화 기준 판매 가격"),
						fieldWithPath("sales[].quantity").description("최초 판매 수량"),
						fieldWithPath("sales[].saleDate").description("Asia/Seoul 기준 판매일"),
						fieldWithPath("sales[].startsAt").description("판매 시작 시각"),
						fieldWithPath("sales[].endsAt").description("판매 종료 시각"),
						fieldWithPath("sales[].status").description("판매 상태"),
						fieldWithPath("sales[].representativeImagePath").description("대표 이미지 상대 경로"),
					),
				),
			)

		verify(saleService).findBuyerSaleProducts(saleDate)
	}

	@Test
	@DisplayName("판매 기록이 없는 유효한 판매일이면 빈 목록을 반환한다")
	fun 판매_기록이_없는_유효한_판매일_빈_목록을_반환한다() {
		val saleDate = LocalDate.parse("2026-09-09")
		`when`(saleService.findBuyerSaleProducts(saleDate)).thenReturn(BuyerSaleProductListResponse(emptyList()))

		mockMvc.perform(get("/api/sales").queryParam("saleDate", "2026-09-09"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.sales").isEmpty)
			.andDo(
				document(
					"buyer-sale-product-list-empty",
					preprocessRequest(prettyPrint()),
					preprocessResponse(prettyPrint()),
					queryParameters(parameterWithName("saleDate").description("Asia/Seoul 기준 조회 판매일(YYYY-MM-DD)")),
					responseFields(fieldWithPath("sales").description("빈 판매 상품 목록")),
				),
			)
	}

	@Test
	@DisplayName("판매일 쿼리 파라미터가 누락되면 공통 요청 파라미터 오류를 반환하고 문서화한다")
	fun 판매일_쿼리_파라미터가_누락_공통_요청_파라미터_오류를_반환하고_문서화한다() {
		assertInvalidSaleListRequest(null, "COMMON_REQUEST_PARAMETER_INVALID")
			.andDo(documentBuyerSaleListError("buyer-sale-product-list-parameter-invalid"))
	}

	@Test
	@DisplayName("판매일 형식이 올바르지 않으면 공통 요청 파라미터 오류를 반환한다")
	fun 판매일_형식이_올바르지_않음_공통_요청_파라미터_오류를_반환한다() {
		assertInvalidSaleListRequest("2026-09-31", "COMMON_REQUEST_PARAMETER_INVALID")
	}

	@Test
	@DisplayName("판매일이 조회 범위를 벗어나면 판매일 범위 오류를 반환하고 문서화한다")
	fun 판매일이_조회_범위를_벗어남_판매일_범위_오류를_반환하고_문서화한다() {
		val saleDate = LocalDate.parse("2026-09-12")
		`when`(saleService.findBuyerSaleProducts(saleDate)).thenThrow(SaleException(SaleErrorCode.DATE_OUT_OF_RANGE))

		assertInvalidSaleListRequest("2026-09-12", "SALE_DATE_OUT_OF_RANGE", "saleDate", "판매일은 내일까지 조회할 수 있습니다.")
			.andDo(documentBuyerSaleListError("buyer-sale-product-list-date-out-of-range", true))
	}

	@Test
	@DisplayName("판매자 헤더가 없으면 공통 헤더 누락 오류를 반환한다")
	fun 판매자_헤더가_없음_공통_헤더_누락_오류를_반환한다() {
		assertInvalidRequest(validRequestBody, null, "COMMON_REQUEST_HEADER_MISSING")
	}

	@Test
	@DisplayName("판매자 헤더가 Long 형식이 아니면 공통 헤더 형식 오류를 반환한다")
	fun 판매자_헤더가_Long_형식이_아님_공통_헤더_형식_오류를_반환한다() {
		assertInvalidRequest(validRequestBody, "9223372036854775808", "COMMON_REQUEST_HEADER_INVALID")
	}

	@Test
	@DisplayName("판매일 형식이 올바르지 않으면 공통 본문 오류를 반환한다")
	fun 판매일_형식이_올바르지_않음_공통_본문_오류를_반환한다() {
		assertInvalidRequest(
			"""{"productId":10,"saleDate":"2026-09-31","price":35000,"quantity":100}""",
			"1",
			"COMMON_REQUEST_BODY_MALFORMED",
		)
	}

	@Test
	@DisplayName("숫자 필드가 문자열이면 공통 본문 오류를 반환한다")
	fun 숫자_필드가_문자열_공통_본문_오류를_반환한다() {
		assertInvalidRequest(
			"""{"productId":"10","saleDate":"2026-09-12","price":35000,"quantity":100}""",
			"1",
			"COMMON_REQUEST_BODY_MALFORMED",
		)
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("필드의미오류요청")
	@DisplayName("필수값 누락·null 또는 양수가 아닌 값이면 필드별 판매 오류를 반환한다")
	fun 필수값_누락_null_양수가_아닌_값_필드별_판매_오류를_반환한다(
		@Suppress("UNUSED_PARAMETER") 설명: String,
		requestBody: String,
		sellerId: String,
		dto: CreateSaleDto,
		errorCode: SaleErrorCode,
		expectedProperty: String,
	) {
		`when`(saleService.create(dto)).thenThrow(SaleException(errorCode))

		assertInvalidRequest(
			requestBody = requestBody,
			sellerId = sellerId,
			expectedCode = errorCode.code,
			expectedProperty = expectedProperty,
			expectedMessage = errorCode.message,
		)
	}

	@Test
	@DisplayName("판매일이 누락되면 판매일 필수 오류를 문서화한다")
	fun 판매일이_누락_판매일_필수_오류를_문서화한다() {
		val dto = CreateSaleDto(1L, 10L, null, 35_000L, 100)
		`when`(saleService.create(dto)).thenThrow(SaleException(SaleErrorCode.DATE_REQUIRED))

		assertInvalidRequest(
			requestBody = """{"productId":10,"price":35000,"quantity":100}""",
			sellerId = "1",
			expectedCode = "SALE_DATE_REQUIRED",
			expectedProperty = "saleDate",
			expectedMessage = "판매일은 필수입니다.",
		).andDo(
			document(
				"sale-create-date-required",
				preprocessRequest(prettyPrint()),
				preprocessResponse(prettyPrint()),
				responseFields(
					fieldWithPath("type").description("오류 유형 URI"),
					fieldWithPath("title").description("오류 제목"),
					fieldWithPath("status").description("HTTP 상태 코드"),
					fieldWithPath("detail").description("오류 설명"),
					fieldWithPath("instance").description("오류가 발생한 요청 경로"),
					fieldWithPath("code").description("안정적인 오류 코드"),
					fieldWithPath("errors.saleDate").description("판매일 오류 메시지"),
				),
			),
		)
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("잘못된숫자요청")
	@DisplayName("숫자 필드가 소수이거나 타입 범위를 벗어나면 공통 본문 오류를 반환한다")
	fun 숫자_필드가_소수이거나_타입_범위를_벗어남_공통_본문_오류를_반환한다(
		@Suppress("UNUSED_PARAMETER") 설명: String,
		requestBody: String,
	) {
		assertInvalidRequest(requestBody, "1", "COMMON_REQUEST_BODY_MALFORMED")
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("주요비즈니스오류")
	@DisplayName("주요 판매 비즈니스 오류이면 계약된 상태와 코드를 반환한다")
	fun 주요_판매_비즈니스_오류_계약된_상태와_코드를_반환한다(
		@Suppress("UNUSED_PARAMETER") 설명: String,
		errorCode: SaleErrorCode,
		expectedStatus: Int,
	) {
		val dto = CreateSaleDto(1L, 10L, LocalDate.parse("2026-09-12"), 35_000L, 100)
		`when`(saleService.create(dto)).thenThrow(SaleException(errorCode))

		assertInvalidRequest(
			requestBody = validRequestBody,
			sellerId = "1",
			expectedCode = errorCode.code,
			expectedStatus = expectedStatus,
			expectedProperty = errorCode.property,
			expectedMessage = errorCode.message,
		)
	}

	@Test
	@DisplayName("판매 도메인 오류이면 기존 ProblemDetail 계약으로 반환한다")
	fun 판매_도메인_오류_기존_ProblemDetail_계약으로_반환한다() {
		`when`(saleService.create(CreateSaleDto(1L, 10L, LocalDate.parse("2026-09-12"), 35_000L, 100)))
			.thenThrow(SaleException(SaleErrorCode.CAPACITY_EXCEEDED))

		assertInvalidRequest(validRequestBody, "1", "SALE_CAPACITY_EXCEEDED", expectedStatus = 409)
			.andDo(
				document(
					"sale-create-capacity-exceeded",
					preprocessRequest(prettyPrint()),
					preprocessResponse(prettyPrint()),
					responseFields(
						fieldWithPath("type").description("오류 유형 URI"),
						fieldWithPath("title").description("오류 제목"),
						fieldWithPath("status").description("HTTP 상태 코드"),
						fieldWithPath("detail").description("오류 설명"),
						fieldWithPath("instance").description("오류가 발생한 요청 경로"),
						fieldWithPath("code").description("안정적인 오류 코드"),
						fieldWithPath("errors.saleDate").description("판매일 오류 메시지"),
					),
				),
			)
	}

	private fun assertInvalidRequest(
		requestBody: String,
		sellerId: String?,
		expectedCode: String,
		expectedStatus: Int = 400,
		expectedProperty: String? = null,
		expectedMessage: String? = null,
	): ResultActions {
		val request = post("/api/sales")
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestBody)
		if (sellerId != null) request.header("X-Seller-Id", sellerId)

		val result = mockMvc.perform(request)
			.andExpect(status().`is`(expectedStatus))
			.andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
			.andExpect(jsonPath("$.type").value("about:blank"))
			.andExpect(jsonPath("$.status").value(expectedStatus))
			.andExpect(jsonPath("$.instance").value("/api/sales"))
			.andExpect(jsonPath("$.code").value(expectedCode))

		if (expectedProperty != null && expectedMessage != null) {
			result.andExpect(jsonPath("$.errors.$expectedProperty").value(expectedMessage))
		}
		return result
	}

	private fun assertInvalidSaleListRequest(
		saleDate: String?,
		expectedCode: String,
		expectedProperty: String? = null,
		expectedMessage: String? = null,
	): ResultActions {
		val request = get("/api/sales")
		if (saleDate != null) request.queryParam("saleDate", saleDate)

		val result = mockMvc.perform(request)
			.andExpect(status().isBadRequest)
			.andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
			.andExpect(jsonPath("$.type").value("about:blank"))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.instance").value("/api/sales"))
			.andExpect(jsonPath("$.code").value(expectedCode))

		if (expectedProperty != null && expectedMessage != null) {
			result.andExpect(jsonPath("$.errors.$expectedProperty").value(expectedMessage))
		}
		return result
	}

	private fun documentBuyerSaleListError(
		identifier: String,
		includeSaleDateError: Boolean = false,
	): RestDocumentationResultHandler {
		val fields = mutableListOf(
			fieldWithPath("type").description("오류 유형 URI"),
			fieldWithPath("title").description("오류 제목"),
			fieldWithPath("status").description("HTTP 상태 코드"),
			fieldWithPath("detail").description("오류 설명"),
			fieldWithPath("instance").description("오류가 발생한 요청 경로"),
			fieldWithPath("code").description("안정적인 오류 코드"),
		)
		if (includeSaleDateError) fields += fieldWithPath("errors.saleDate").description("판매일 오류 메시지")
		return document(
			identifier,
			preprocessRequest(prettyPrint()),
			preprocessResponse(prettyPrint()),
			responseFields(fields),
		)
	}

	private companion object {
		const val validRequestBody =
			"""{"productId":10,"saleDate":"2026-09-12","price":35000,"quantity":100}"""

		@JvmStatic
		fun 필드의미오류요청(): List<Arguments> = listOf(
			Arguments.of("상품 식별자 누락", """{"saleDate":"2026-09-12","price":35000,"quantity":100}""", "1", CreateSaleDto(1L, null, LocalDate.parse("2026-09-12"), 35_000L, 100), SaleErrorCode.PRODUCT_ID_INVALID, "productId"),
			Arguments.of("상품 식별자 null", """{"productId":null,"saleDate":"2026-09-12","price":35000,"quantity":100}""", "1", CreateSaleDto(1L, null, LocalDate.parse("2026-09-12"), 35_000L, 100), SaleErrorCode.PRODUCT_ID_INVALID, "productId"),
			Arguments.of("상품 식별자 0", """{"productId":0,"saleDate":"2026-09-12","price":35000,"quantity":100}""", "1", CreateSaleDto(1L, 0L, LocalDate.parse("2026-09-12"), 35_000L, 100), SaleErrorCode.PRODUCT_ID_INVALID, "productId"),
			Arguments.of("상품 식별자 음수", """{"productId":-1,"saleDate":"2026-09-12","price":35000,"quantity":100}""", "1", CreateSaleDto(1L, -1L, LocalDate.parse("2026-09-12"), 35_000L, 100), SaleErrorCode.PRODUCT_ID_INVALID, "productId"),
			Arguments.of("판매일 누락", """{"productId":10,"price":35000,"quantity":100}""", "1", CreateSaleDto(1L, 10L, null, 35_000L, 100), SaleErrorCode.DATE_REQUIRED, "saleDate"),
			Arguments.of("판매일 null", """{"productId":10,"saleDate":null,"price":35000,"quantity":100}""", "1", CreateSaleDto(1L, 10L, null, 35_000L, 100), SaleErrorCode.DATE_REQUIRED, "saleDate"),
			Arguments.of("가격 누락", """{"productId":10,"saleDate":"2026-09-12","quantity":100}""", "1", CreateSaleDto(1L, 10L, LocalDate.parse("2026-09-12"), null, 100), SaleErrorCode.PRICE_INVALID, "price"),
			Arguments.of("가격 null", """{"productId":10,"saleDate":"2026-09-12","price":null,"quantity":100}""", "1", CreateSaleDto(1L, 10L, LocalDate.parse("2026-09-12"), null, 100), SaleErrorCode.PRICE_INVALID, "price"),
			Arguments.of("가격 0", """{"productId":10,"saleDate":"2026-09-12","price":0,"quantity":100}""", "1", CreateSaleDto(1L, 10L, LocalDate.parse("2026-09-12"), 0L, 100), SaleErrorCode.PRICE_INVALID, "price"),
			Arguments.of("가격 음수", """{"productId":10,"saleDate":"2026-09-12","price":-1,"quantity":100}""", "1", CreateSaleDto(1L, 10L, LocalDate.parse("2026-09-12"), -1L, 100), SaleErrorCode.PRICE_INVALID, "price"),
			Arguments.of("수량 누락", """{"productId":10,"saleDate":"2026-09-12","price":35000}""", "1", CreateSaleDto(1L, 10L, LocalDate.parse("2026-09-12"), 35_000L, null), SaleErrorCode.QUANTITY_INVALID, "quantity"),
			Arguments.of("수량 null", """{"productId":10,"saleDate":"2026-09-12","price":35000,"quantity":null}""", "1", CreateSaleDto(1L, 10L, LocalDate.parse("2026-09-12"), 35_000L, null), SaleErrorCode.QUANTITY_INVALID, "quantity"),
			Arguments.of("수량 0", """{"productId":10,"saleDate":"2026-09-12","price":35000,"quantity":0}""", "1", CreateSaleDto(1L, 10L, LocalDate.parse("2026-09-12"), 35_000L, 0), SaleErrorCode.QUANTITY_INVALID, "quantity"),
			Arguments.of("수량 음수", """{"productId":10,"saleDate":"2026-09-12","price":35000,"quantity":-1}""", "1", CreateSaleDto(1L, 10L, LocalDate.parse("2026-09-12"), 35_000L, -1), SaleErrorCode.QUANTITY_INVALID, "quantity"),
			Arguments.of("판매자 식별자 0", validRequestBody, "0", CreateSaleDto(0L, 10L, LocalDate.parse("2026-09-12"), 35_000L, 100), SaleErrorCode.SELLER_ID_INVALID, "sellerId"),
			Arguments.of("판매자 식별자 음수", validRequestBody, "-1", CreateSaleDto(-1L, 10L, LocalDate.parse("2026-09-12"), 35_000L, 100), SaleErrorCode.SELLER_ID_INVALID, "sellerId"),
		)

		@JvmStatic
		fun 잘못된숫자요청(): List<Arguments> = listOf(
			Arguments.of("상품 식별자 소수", """{"productId":10.5,"saleDate":"2026-09-12","price":35000,"quantity":100}"""),
			Arguments.of("가격 소수", """{"productId":10,"saleDate":"2026-09-12","price":35000.5,"quantity":100}"""),
			Arguments.of("수량 소수", """{"productId":10,"saleDate":"2026-09-12","price":35000,"quantity":100.5}"""),
			Arguments.of("상품 식별자 Long 범위 초과", """{"productId":9223372036854775808,"saleDate":"2026-09-12","price":35000,"quantity":100}"""),
			Arguments.of("가격 Long 범위 초과", """{"productId":10,"saleDate":"2026-09-12","price":9223372036854775808,"quantity":100}"""),
			Arguments.of("수량 Int 범위 초과", """{"productId":10,"saleDate":"2026-09-12","price":35000,"quantity":2147483648}"""),
		)

		@JvmStatic
		fun 주요비즈니스오류(): List<Arguments> = listOf(
			Arguments.of("상품 없음", SaleErrorCode.PRODUCT_NOT_FOUND, 404),
			Arguments.of("상품 준비 전", SaleErrorCode.PRODUCT_NOT_READY, 409),
			Arguments.of("등록 시간 아님", SaleErrorCode.REGISTRATION_CLOSED, 409),
			Arguments.of("판매자 중복", SaleErrorCode.SELLER_ALREADY_REGISTERED, 409),
		)
	}
}
