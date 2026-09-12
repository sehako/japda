package io.github.sehako.japda.shippingaddress.presentation.controller

import io.github.sehako.japda.global.error.GlobalExceptionHandler
import io.github.sehako.japda.global.error.ProblemDetailFactory
import io.github.sehako.japda.shippingaddress.application.dto.CreateBuyerShippingAddressDto
import io.github.sehako.japda.shippingaddress.application.response.BuyerShippingAddressResponse
import io.github.sehako.japda.shippingaddress.application.service.BuyerShippingAddressService
import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressErrorCode
import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressException
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
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
import org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest
import org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse
import org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder

@DisplayName("구매자 배송지 API")
@ExtendWith(RestDocumentationExtension::class)
class ShippingAddressControllerTest {
	private lateinit var mockMvc: MockMvc
	private lateinit var service: BuyerShippingAddressService

	@BeforeEach
	fun setUp(restDocumentation: RestDocumentationContextProvider) {
		service = mock(BuyerShippingAddressService::class.java)
		mockMvc = MockMvcBuilders.standaloneSetup(ShippingAddressController(service))
			.setControllerAdvice(GlobalExceptionHandler(ProblemDetailFactory()))
			.apply<StandaloneMockMvcBuilder>(documentationConfiguration(restDocumentation))
			.build()
	}

	@Test
	@DisplayName("유효한 요청이면 배송지를 등록하고 201 응답을 문서화한다")
	fun 유효한_요청_배송지_등록_201_응답을_문서화한다() {
		`when`(service.create(EXPECTED_DTO)).thenReturn(RESPONSE)

		mockMvc.perform(validRequest())
			.andExpect(status().isCreated)
			.andExpect(header().doesNotExist("Location"))
			.andExpect(jsonPath("$.shippingAddressId").value(1))
			.andExpect(jsonPath("$.addressName").value("집"))
			.andExpect(jsonPath("$.createdAt").value(NOW.toString()))
			.andExpect(jsonPath("$.buyerId").doesNotExist())
			.andDo(
				document(
					"buyer-shipping-address-create",
					preprocessRequest(prettyPrint()),
					preprocessResponse(prettyPrint()),
					requestHeaders(headerWithName("X-Buyer-Id").description("임시 구매자 식별자")),
					requestFields(
						fieldWithPath("addressName").description("배송지명"),
						fieldWithPath("recipientName").description("수령인 이름"),
						fieldWithPath("phoneNumber").description("전화번호"),
						fieldWithPath("postalCode").description("우편번호"),
						fieldWithPath("address").description("주소"),
						fieldWithPath("detailAddress").description("상세 주소"),
						fieldWithPath("deliveryMessage").description("배송 메시지").optional(),
					),
					responseFields(
						fieldWithPath("shippingAddressId").description("배송지 식별자"),
						fieldWithPath("addressName").description("정규화된 배송지명"),
						fieldWithPath("recipientName").description("정규화된 수령인 이름"),
						fieldWithPath("phoneNumber").description("정규화된 전화번호"),
						fieldWithPath("postalCode").description("정규화된 우편번호"),
						fieldWithPath("address").description("정규화된 주소"),
						fieldWithPath("detailAddress").description("정규화된 상세 주소"),
						fieldWithPath("deliveryMessage").description("정규화된 배송 메시지").optional(),
						fieldWithPath("createdAt").description("생성 시각"),
					),
				),
			)

		verify(service).create(EXPECTED_DTO)
	}

	@Test
	@DisplayName("구매자 헤더가 없으면 공통 헤더 누락 오류를 반환한다")
	fun 구매자_헤더_없음_공통_헤더_누락_오류를_반환한다() {
		mockMvc.perform(post("/api/shipping-addresses").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("COMMON_REQUEST_HEADER_MISSING"))
	}

	@Test
	@DisplayName("구매자 헤더가 양의 Long이 아니면 공통 헤더 형식 오류를 반환한다")
	fun 구매자_헤더_양의_Long_아님_공통_헤더_형식_오류를_반환한다() {
		listOf("0", "abc").forEach { value ->
			mockMvc.perform(validRequest().header("X-Buyer-Id", value))
				.andExpect(status().isBadRequest)
				.andExpect(jsonPath("$.code").value("COMMON_REQUEST_HEADER_INVALID"))
		}
	}

	@Test
	@DisplayName("잘못된 JSON이면 공통 본문 오류를 반환한다")
	fun 잘못된_JSON_공통_본문_오류를_반환한다() {
		mockMvc.perform(validRequest("{"))
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("COMMON_REQUEST_BODY_MALFORMED"))
	}

	@Test
	@DisplayName("배송지명 중복이면 409 오류를 문서화한다")
	fun 배송지명_중복_409_오류를_문서화한다() {
		`when`(service.create(EXPECTED_DTO)).thenThrow(BuyerShippingAddressException(BuyerShippingAddressErrorCode.NAME_DUPLICATED))

		mockMvc.perform(validRequest())
			.andExpect(status().isConflict)
			.andExpect(jsonPath("$.code").value("BUYER_SHIPPING_ADDRESS_NAME_DUPLICATED"))
			.andExpect(jsonPath("$.errors.addressName").exists())
			.andDo(document("buyer-shipping-address-create-name-duplicated", preprocessResponse(prettyPrint()), problemFields("errors.addressName")))
	}

	@Test
	@DisplayName("배송지 개수 초과이면 409 오류를 문서화한다")
	fun 배송지_개수_초과_409_오류를_문서화한다() {
		`when`(service.create(EXPECTED_DTO)).thenThrow(BuyerShippingAddressException(BuyerShippingAddressErrorCode.LIMIT_EXCEEDED))

		mockMvc.perform(validRequest())
			.andExpect(status().isConflict)
			.andExpect(jsonPath("$.code").value("BUYER_SHIPPING_ADDRESS_LIMIT_EXCEEDED"))
			.andDo(document("buyer-shipping-address-create-limit-exceeded", preprocessResponse(prettyPrint()), problemFields()))
	}

	private fun problemFields(errorPath: String? = null) = responseFields(
		fieldWithPath("type").description("오류 유형 URI"),
		fieldWithPath("title").description("오류 제목"),
		fieldWithPath("status").description("HTTP 상태 코드"),
		fieldWithPath("detail").description("오류 설명"),
		fieldWithPath("instance").description("오류 요청 경로"),
		fieldWithPath("code").description("안정적인 오류 코드"),
		*listOfNotNull(errorPath?.let { fieldWithPath(it).description("배송지명 오류 메시지") }).toTypedArray(),
	)

	private fun validRequest(body: String = VALID_BODY) = post("/api/shipping-addresses")
		.header("X-Buyer-Id", "123")
		.contentType(MediaType.APPLICATION_JSON)
		.content(body)

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-12T03:34:56Z")
		val VALID_BODY = """{"addressName":"집","recipientName":"홍길동","phoneNumber":"010-1234-5678","postalCode":"06236","address":"서울시 강남구","detailAddress":"101호","deliveryMessage":"문 앞"}"""
		val EXPECTED_DTO = CreateBuyerShippingAddressDto(123L, "집", "홍길동", "010-1234-5678", "06236", "서울시 강남구", "101호", "문 앞")
		val RESPONSE = BuyerShippingAddressResponse(1L, "집", "홍길동", "010-1234-5678", "06236", "서울시 강남구", "101호", "문 앞", NOW)
	}
}
