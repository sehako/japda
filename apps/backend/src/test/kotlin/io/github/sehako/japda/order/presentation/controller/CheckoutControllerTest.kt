package io.github.sehako.japda.order.presentation.controller

import io.github.sehako.japda.global.error.GlobalExceptionHandler
import io.github.sehako.japda.global.error.ProblemDetailFactory
import io.github.sehako.japda.order.application.response.CheckoutResponse
import io.github.sehako.japda.order.application.response.CheckoutShippingAddressResponse
import io.github.sehako.japda.order.application.service.CheckoutService
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse
import org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder

@DisplayName("체크아웃 API")
@ExtendWith(RestDocumentationExtension::class)
class CheckoutControllerTest {
	private lateinit var mockMvc: MockMvc
	private lateinit var service: CheckoutService

	@BeforeEach
	fun setUp(restDocumentation: RestDocumentationContextProvider) {
		service = mock(CheckoutService::class.java)
		mockMvc = MockMvcBuilders.standaloneSetup(CheckoutController(service))
			.setControllerAdvice(GlobalExceptionHandler(ProblemDetailFactory()))
			.apply<StandaloneMockMvcBuilder>(documentationConfiguration(restDocumentation))
			.build()
	}

	@Test
	@DisplayName("유효한 요청은 상품, 예상 금액과 구매자 배송지를 반환하고 문서화한다")
	fun 유효한_요청_상품과_예상_금액과_배송지를_반환하고_문서화한다() {
		`when`(service.find(123, 100, 2)).thenReturn(RESPONSE)

		mockMvc.perform(validRequest())
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.saleId").value(100))
			.andExpect(jsonPath("$.productName").value("한정판 상품"))
			.andExpect(jsonPath("$.representativeImagePath").value("/products/42/image-a"))
			.andExpect(jsonPath("$.quantity").value(2))
			.andExpect(jsonPath("$.unitPrice").value(35000))
			.andExpect(jsonPath("$.totalPrice").value(70000))
			.andExpect(jsonPath("$.shippingAddresses[0].shippingAddressId").value(7))
			.andExpect(jsonPath("$.shippingAddresses[0].deliveryMessage").value("문 앞"))
			.andExpect(jsonPath("$.buyerId").doesNotExist())
			.andDo(document("checkout-get", preprocessResponse(prettyPrint()),
				requestHeaders(headerWithName("X-Buyer-Id").description("임시 구매자 식별자")),
				queryParameters(
					parameterWithName("saleId").description("판매 일정 식별자"),
					parameterWithName("quantity").description("구매 수량"),
				),
				responseFields(
					fieldWithPath("saleId").description("판매 일정 식별자"),
					fieldWithPath("productName").description("현재 상품명"),
					fieldWithPath("representativeImagePath").description("대표 이미지 상대 경로"),
					fieldWithPath("quantity").description("요청 수량"),
					fieldWithPath("unitPrice").description("현재 판매 단가"),
					fieldWithPath("totalPrice").description("예상 총액"),
					fieldWithPath("shippingAddresses").description("저장 배송지 목록"),
					fieldWithPath("shippingAddresses[].shippingAddressId").description("저장 배송지 식별자"),
					fieldWithPath("shippingAddresses[].addressName").description("배송지명"),
					fieldWithPath("shippingAddresses[].recipientName").description("수취인명"),
					fieldWithPath("shippingAddresses[].phoneNumber").description("전화번호"),
					fieldWithPath("shippingAddresses[].postalCode").description("우편번호"),
					fieldWithPath("shippingAddresses[].address").description("기본 주소"),
					fieldWithPath("shippingAddresses[].detailAddress").description("상세 주소"),
					fieldWithPath("shippingAddresses[].deliveryMessage").description("배송 메모").optional(),
				),
			))
	}

	@Test
	@DisplayName("구매자 헤더가 없으면 헤더 누락 오류를 반환한다")
	fun 구매자_헤더_없으면_누락_오류를_반환한다() {
		mockMvc.perform(get("/api/checkout").param("saleId", "100").param("quantity", "2"))
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("COMMON_REQUEST_HEADER_MISSING"))
	}

	@Test
	@DisplayName("구매자 헤더가 양수가 아니거나 형식이 틀리면 헤더 오류를 반환한다")
	fun 구매자_헤더_잘못되면_형식_오류를_반환한다() {
		for (value in listOf("0", "abc")) {
			mockMvc.perform(validRequest().header("X-Buyer-Id", value))
				.andExpect(status().isBadRequest)
				.andExpect(jsonPath("$.code").value("COMMON_REQUEST_HEADER_INVALID"))
		}
	}

	@Test
	@DisplayName("쿼리 매개변수가 없거나 숫자가 아니면 매개변수 오류를 반환한다")
	fun 쿼리_매개변수_누락이나_형식_오류를_반환한다() {
		for (request in listOf(
			get("/api/checkout").header("X-Buyer-Id", "123").param("quantity", "2"),
			get("/api/checkout").header("X-Buyer-Id", "123").param("saleId", "100").param("quantity", "abc"),
		)) {
			mockMvc.perform(request)
				.andExpect(status().isBadRequest)
				.andExpect(jsonPath("$.code").value("COMMON_REQUEST_PARAMETER_INVALID"))
		}
	}

	@Test
	@DisplayName("판매 일정 미존재 오류를 문서화한다")
	fun 판매_일정_미존재_오류를_문서화한다() {
		`when`(service.find(123, 100, 2)).thenThrow(OrderException(OrderErrorCode.SALE_NOT_FOUND))

		mockMvc.perform(validRequest())
			.andExpect(status().isNotFound)
			.andExpect(jsonPath("$.code").value("ORDER_SALE_NOT_FOUND"))
			.andExpect(jsonPath("$.errors.saleId").exists())
			.andDo(document("checkout-get-sale-not-found", preprocessResponse(prettyPrint()), errorFields("saleId")))
	}

	@Test
	@DisplayName("예상 총액 범위 초과 오류를 문서화한다")
	fun 예상_총액_범위_초과_오류를_문서화한다() {
		`when`(service.find(123, 100, 2)).thenThrow(OrderException(OrderErrorCode.TOTAL_PRICE_INVALID))

		mockMvc.perform(validRequest())
			.andExpect(status().isConflict)
			.andExpect(jsonPath("$.code").value("ORDER_TOTAL_PRICE_INVALID"))
			.andDo(document("checkout-get-total-price-invalid", preprocessResponse(prettyPrint()), errorFields()))
	}

	private fun validRequest() = get("/api/checkout")
		.header("X-Buyer-Id", "123")
		.param("saleId", "100")
		.param("quantity", "2")

	private fun errorFields(property: String? = null) = responseFields(
		fieldWithPath("type").description("오류 유형 URI"),
		fieldWithPath("title").description("오류 제목"),
		fieldWithPath("status").description("HTTP 상태 코드"),
		fieldWithPath("detail").description("오류 설명"),
		fieldWithPath("instance").description("오류 요청 경로"),
		fieldWithPath("code").description("오류 코드"),
		*if (property == null) emptyArray() else arrayOf(fieldWithPath("errors.$property").description("요청 값 오류")),
	)

	private companion object {
		val RESPONSE = CheckoutResponse(100, "한정판 상품", "/products/42/image-a", 2, 35_000, 70_000,
			listOf(CheckoutShippingAddressResponse(7, "집", "홍길동", "010-1234-5678", "06236", "서울특별시 강남구", "101호", "문 앞")))
	}
}
