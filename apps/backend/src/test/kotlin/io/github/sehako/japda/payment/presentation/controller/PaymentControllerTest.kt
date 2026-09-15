package io.github.sehako.japda.payment.presentation.controller

import io.github.sehako.japda.auth.application.service.PrincipalIdentityService
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.error.GlobalExceptionHandler
import io.github.sehako.japda.global.error.ProblemDetailFactory
import io.github.sehako.japda.global.exception.BusinessException
import io.github.sehako.japda.payment.application.dto.ConfirmPaymentDto
import io.github.sehako.japda.payment.application.response.PaymentResponse
import io.github.sehako.japda.payment.application.service.PaymentService
import io.github.sehako.japda.payment.exception.PaymentErrorCode
import io.github.sehako.japda.payment.exception.PaymentException
import java.time.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder

@DisplayName("구매자 결제 승인 API")
@ExtendWith(RestDocumentationExtension::class)
class PaymentControllerTest {
	private lateinit var mvc: MockMvc
	private lateinit var service: PaymentService
	private lateinit var principalIdentityService: PrincipalIdentityService

	@AfterEach
	fun 인증_주체를_초기화한다() = SecurityContextHolder.clearContext()

	@BeforeEach
	fun 준비(restDocumentation: RestDocumentationContextProvider) {
		SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(17L, null)
		service = mock(PaymentService::class.java)
		principalIdentityService = mock(PrincipalIdentityService::class.java)
		`when`(principalIdentityService.buyerId(17L)).thenReturn(123L)
		mvc = MockMvcBuilders.standaloneSetup(PaymentController(service, principalIdentityService))
			.setControllerAdvice(GlobalExceptionHandler(ProblemDetailFactory()))
			.setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
			.apply<StandaloneMockMvcBuilder>(documentationConfiguration(restDocumentation))
			.build()
	}

	@Test
	@DisplayName("승인된 주문의 식별자, 금액과 승인 시각을 반환한다")
	fun 승인된_주문_식별자_금액과_승인_시각을_반환한다() {
		`when`(service.confirm(ConfirmPaymentDto(123L, "payment-key", ORDER_ID, 70_000L))).thenReturn(
			PaymentResponse(1000L, ORDER_ID, "PAID", 70_000L, Instant.parse("2026-09-13T06:00:00Z")),
		)

		mvc.perform(request())
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.orderId").value(1000))
			.andExpect(jsonPath("$.paymentOrderId").value(ORDER_ID))
			.andExpect(jsonPath("$.status").value("PAID"))
			.andExpect(jsonPath("$.totalAmount").value(70000))
			.andExpect(jsonPath("$.approvedAt").value("2026-09-13T06:00:00Z"))
			.andExpect(jsonPath("$.tossIdempotencyKey").doesNotExist())
			.andDo(document("payment-confirm",
				requestHeaders(
					headerWithName("Cookie").description("JAPDA_ACCESS_TOKEN 인증 쿠키와 XSRF-TOKEN CSRF 쿠키"),
					headerWithName("X-CSRF-TOKEN").description("GET /api/auth/csrf에서 받은 CSRF 토큰"),
				),
				requestFields(
					fieldWithPath("paymentKey").description("토스 결제 키"),
					fieldWithPath("orderId").description("주문 생성 응답의 결제 주문 식별자"),
					fieldWithPath("amount").description("클라이언트가 인증한 결제 금액"),
				),
				responseFields(
					fieldWithPath("orderId").description("내부 주문 식별자"),
					fieldWithPath("paymentOrderId").description("토스 결제 주문 식별자"),
					fieldWithPath("status").description("주문 상태"),
					fieldWithPath("totalAmount").description("승인된 주문 총액"),
					fieldWithPath("approvedAt").description("토스 승인 시각"),
				),
			))
	}

	@Test
	@DisplayName("인증 주체가 없으면 구매자 헤더가 있어도 인증 실패를 반환한다")
	fun 인증_주체_없음_인증_실패를_반환한다() {
		SecurityContextHolder.clearContext()
		mvc.perform(request().header("X-Buyer-Id", "123"))
			.andExpect(status().isUnauthorized)
			.andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
			.andDo(document("payment-confirm-unauthenticated", authErrorHeaders(), errorFields()))
	}

	@Test
	@DisplayName("구매자 헤더를 위조해도 인증 주체의 구매자로 결제한다")
	fun 구매자_헤더_위조_연결된_구매자로_결제한다() {
		`when`(service.confirm(ConfirmPaymentDto(123L, "payment-key", ORDER_ID, 70_000L))).thenReturn(
			PaymentResponse(1000L, ORDER_ID, "PAID", 70_000L, Instant.parse("2026-09-13T06:00:00Z")),
		)
		mvc.perform(request().header("X-Buyer-Id", "999"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.orderId").value(1000))
	}

	@Test
	@DisplayName("구매자 연결이 없으면 연결 필요 오류를 반환한다")
	fun 구매자_연결_없음_연결_필요_오류를_반환한다() {
		doThrow(BusinessException(AuthErrorCode.BUYER_LINK_REQUIRED)).`when`(principalIdentityService).buyerId(17L)
		mvc.perform(request())
			.andExpect(status().isForbidden)
			.andExpect(jsonPath("$.code").value("AUTH_BUYER_LINK_REQUIRED"))
			.andDo(document("payment-confirm-buyer-link-required", authErrorHeaders(), errorFields()))
	}

	@Test
	@DisplayName("금액이 문자열이면 잘못된 본문 오류를 반환한다")
	fun 금액_문자열_잘못된_본문_오류를_반환한다() {
		mvc.perform(request(BODY.replace("70000", "\"70000\"")))
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("COMMON_REQUEST_BODY_MALFORMED"))
	}

	@Test
	@DisplayName("토스 결과가 불명확하면 503과 안정적인 오류 코드를 반환한다")
	fun 토스_결과_불명확_503과_오류_코드를_반환한다() {
		`when`(service.confirm(ConfirmPaymentDto(123L, "payment-key", ORDER_ID, 70_000L)))
			.thenThrow(PaymentException(PaymentErrorCode.UNAVAILABLE))

		mvc.perform(request())
			.andExpect(status().isServiceUnavailable)
			.andExpect(jsonPath("$.code").value("PAYMENT_CONFIRMATION_UNAVAILABLE"))
			.andExpect(jsonPath("$.paymentKey").doesNotExist())
			.andDo(document("payment-confirm-unavailable", responseFields(
				fieldWithPath("type").description("오류 유형 URI"),
				fieldWithPath("title").description("오류 제목"),
				fieldWithPath("status").description("HTTP 상태 코드"),
				fieldWithPath("detail").description("오류 설명"),
				fieldWithPath("instance").description("요청 경로"),
				fieldWithPath("code").description("오류 코드"),
			)))
	}

	private fun request(body: String = BODY) = post("/api/payments/confirm")
		.header("Cookie", "JAPDA_ACCESS_TOKEN=<JWT>; XSRF-TOKEN=<CSRF>")
		.header("X-CSRF-TOKEN", "<CSRF>")
		.contentType(MediaType.APPLICATION_JSON)
		.content(body)

	private fun errorFields() = responseFields(
		fieldWithPath("type").description("오류 유형 URI"),
		fieldWithPath("title").description("오류 제목"),
		fieldWithPath("status").description("HTTP 상태 코드"),
		fieldWithPath("detail").description("오류 설명"),
		fieldWithPath("instance").description("요청 경로"),
		fieldWithPath("code").description("오류 코드"),
	)

	private fun authErrorHeaders() = responseHeaders(
		headerWithName("Content-Type").description("application/problem+json"),
		headerWithName("Cache-Control").description("no-store"),
	)

	private companion object {
		const val ORDER_ID = "550e8400-e29b-41d4-a716-446655440000"
		const val BODY = """{"paymentKey":"payment-key","orderId":"$ORDER_ID","amount":70000}"""
	}
}
