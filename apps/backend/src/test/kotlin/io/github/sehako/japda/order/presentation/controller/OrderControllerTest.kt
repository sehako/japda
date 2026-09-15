package io.github.sehako.japda.order.presentation.controller

import io.github.sehako.japda.auth.application.service.PrincipalIdentityService
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.exception.BusinessException
import io.github.sehako.japda.global.error.GlobalExceptionHandler
import io.github.sehako.japda.global.error.ProblemDetailFactory
import io.github.sehako.japda.order.application.dto.CreateOrderDto
import io.github.sehako.japda.order.application.response.OrderResponse
import io.github.sehako.japda.order.application.service.OrderService
import io.github.sehako.japda.order.domain.model.OrderStatus
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder

@DisplayName("주문 API")
@ExtendWith(RestDocumentationExtension::class)
class OrderControllerTest {
	private lateinit var mockMvc: MockMvc
	private lateinit var orderService: OrderService
	private lateinit var principalIdentityService: PrincipalIdentityService

	@AfterEach
	fun 인증_주체를_초기화한다() = SecurityContextHolder.clearContext()

	@BeforeEach
	fun setUp(restDocumentation: RestDocumentationContextProvider) {
		SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(17L, null)
		orderService = mock(OrderService::class.java)
		principalIdentityService = mock(PrincipalIdentityService::class.java)
		`when`(principalIdentityService.buyerId(17L)).thenReturn(123L)
		mockMvc = MockMvcBuilders.standaloneSetup(OrderController(orderService, principalIdentityService))
			.setControllerAdvice(GlobalExceptionHandler(ProblemDetailFactory()))
			.setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
			.apply<StandaloneMockMvcBuilder>(documentationConfiguration(restDocumentation))
			.build()
	}

	@Test
	@DisplayName("인증된 사용자는 구매자 헤더 없이 주문을 생성한다")
	fun 인증_주체_구매자_헤더_없이_주문을_생성한다() {
		SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(17L, null)
		`when`(orderService.create(EXPECTED_DTO)).thenReturn(
			OrderResponse(1000L, PAYMENT_ORDER_ID, OrderStatus.PENDING_PAYMENT, "한정판 상품", 2, 35_000L, 70_000L, EXPIRES_AT),
		)

		mockMvc.perform(post("/api/orders")
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.orderId").value(1000))
	}

	@Test
	@DisplayName("유효한 요청이면 주문을 생성하고 배송 정보 없이 201 응답을 반환한다")
	fun 유효한_요청_주문_생성_배송_정보_없이_201을_반환한다() {
		`when`(orderService.create(EXPECTED_DTO)).thenReturn(
			OrderResponse(1000L, PAYMENT_ORDER_ID, OrderStatus.PENDING_PAYMENT, "한정판 상품", 2, 35_000L, 70_000L, EXPIRES_AT),
		)

		mockMvc.perform(validRequest())
			.andExpect(status().isCreated)
			.andExpect(header().doesNotExist("Location"))
			.andExpect(jsonPath("$.orderId").value(1000))
			.andExpect(jsonPath("$.paymentOrderId").value(PAYMENT_ORDER_ID))
			.andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
			.andExpect(jsonPath("$.productName").value("한정판 상품"))
			.andExpect(jsonPath("$.quantity").value(2))
			.andExpect(jsonPath("$.unitPrice").value(35000))
			.andExpect(jsonPath("$.totalPrice").value(70000))
			.andExpect(jsonPath("$.expiresAt").value("2026-09-11T06:03:00Z"))
			.andExpect(jsonPath("$.shippingAddress").doesNotExist())
			.andDo(
				document(
					"order-create",
					preprocessRequest(prettyPrint()),
					preprocessResponse(prettyPrint()),
					requestHeaders(
						headerWithName("Cookie").description("JAPDA_ACCESS_TOKEN 인증 쿠키와 XSRF-TOKEN CSRF 쿠키"),
						headerWithName("X-CSRF-TOKEN").description("GET /api/auth/csrf에서 받은 CSRF 토큰"),
						headerWithName("Idempotency-Key").description("주문 생성 멱등성 UUID"),
					),
					requestFields(
						fieldWithPath("saleId").description("판매 일정 식별자"),
						fieldWithPath("quantity").description("주문 수량"),
						fieldWithPath("shippingAddress.recipientName").description("수령인 이름"),
						fieldWithPath("shippingAddress.phoneNumber").description("전화번호"),
						fieldWithPath("shippingAddress.postalCode").description("우편번호"),
						fieldWithPath("shippingAddress.address").description("주소"),
						fieldWithPath("shippingAddress.detailAddress").description("상세 주소"),
						fieldWithPath("shippingAddress.deliveryMessage").description("배송 메시지").optional(),
					),
					responseFields(
						fieldWithPath("orderId").description("주문 식별자"),
						fieldWithPath("paymentOrderId").description("토스페이먼츠 결제 요청용 주문 식별자"),
						fieldWithPath("status").description("주문 상태"),
						fieldWithPath("productName").description("주문 시점 상품명"),
						fieldWithPath("quantity").description("주문 수량"),
						fieldWithPath("unitPrice").description("주문 시점 단가"),
						fieldWithPath("totalPrice").description("주문 총액"),
						fieldWithPath("expiresAt").description("예약 만료 시각"),
					),
				),
			)

		verify(orderService).create(EXPECTED_DTO)
	}

	@Test
	@DisplayName("인증 주체가 없으면 구매자 헤더가 있어도 인증 실패를 반환하고 문서화한다")
	fun 인증_주체_없음_인증_실패를_반환한다() {
		SecurityContextHolder.clearContext()
		mockMvc.perform(
			post("/api/orders")
				.header("X-Buyer-Id", "123")
				.header("Idempotency-Key", IDEMPOTENCY_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(VALID_BODY),
		)
			.andExpect(status().isUnauthorized)
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
			.andDo(document("order-create-unauthenticated", preprocessResponse(prettyPrint()), authErrorHeaders(), errorFields()))
	}

	@Test
	@DisplayName("구매자 헤더를 위조해도 인증 주체의 구매자로 주문한다")
	fun 구매자_헤더_위조_연결된_구매자로_주문한다() {
		`when`(orderService.create(EXPECTED_DTO)).thenReturn(
			OrderResponse(1000L, PAYMENT_ORDER_ID, OrderStatus.PENDING_PAYMENT, "한정판 상품", 2, 35_000L, 70_000L, EXPIRES_AT),
		)
		mockMvc.perform(validRequest().header("X-Buyer-Id", "0"))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.orderId").value(1000))
	}

	@Test
	@DisplayName("구매자 연결이 없으면 연결 필요 오류를 반환하고 문서화한다")
	fun 구매자_연결_없음_연결_필요_오류를_반환한다() {
		doThrow(BusinessException(AuthErrorCode.BUYER_LINK_REQUIRED)).`when`(principalIdentityService).buyerId(17L)
		mockMvc.perform(validRequest())
			.andExpect(status().isForbidden)
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.code").value("AUTH_BUYER_LINK_REQUIRED"))
			.andDo(document("order-create-buyer-link-required", preprocessResponse(prettyPrint()), authErrorHeaders(), errorFields()))
	}

	@Test
	@DisplayName("멱등성 키가 UUID 형식이 아니면 공통 헤더 형식 오류를 반환한다")
	fun 멱등성_키_UUID_형식_아님_공통_헤더_형식_오류를_반환한다() {
		mockMvc.perform(validRequest().header("Idempotency-Key", "not-a-uuid"))
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("COMMON_REQUEST_HEADER_INVALID"))
	}

	@Test
	@DisplayName("배송 객체가 없으면 공통 본문 오류를 반환한다")
	fun 배송_객체_없음_공통_본문_오류를_반환한다() {
		mockMvc.perform(
			post("/api/orders")
				.header("Idempotency-Key", IDEMPOTENCY_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"saleId":100,"quantity":2}"""),
		)
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("COMMON_REQUEST_BODY_MALFORMED"))
	}

	@Test
	@DisplayName("수량이 문자열이면 공통 본문 오류를 반환한다")
	fun 수량_문자열_공통_본문_오류를_반환한다() {
		mockMvc.perform(validRequest(VALID_BODY.replace("\"quantity\":2", "\"quantity\":\"2\"")))
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("COMMON_REQUEST_BODY_MALFORMED"))
	}

	@Test
	@DisplayName("배송 필드 오류이면 필드별 주문 오류를 반환하고 문서화한다")
	fun 배송_필드_오류_필드별_주문_오류를_반환하고_문서화한다() {
		`when`(orderService.create(EXPECTED_DTO)).thenThrow(OrderException(OrderErrorCode.RECIPIENT_NAME_INVALID))

		mockMvc.perform(validRequest())
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("ORDER_RECIPIENT_NAME_INVALID"))
			.andExpect(jsonPath("$.errors.recipientName").exists())
			.andDo(
				document(
					"order-create-recipient-name-invalid",
					preprocessRequest(prettyPrint()),
					preprocessResponse(prettyPrint()),
					responseFields(
						fieldWithPath("type").description("오류 유형 URI"),
						fieldWithPath("title").description("오류 제목"),
						fieldWithPath("status").description("HTTP 상태 코드"),
						fieldWithPath("detail").description("오류 설명"),
						fieldWithPath("instance").description("오류 요청 경로"),
						fieldWithPath("code").description("안정적인 오류 코드"),
						fieldWithPath("errors.recipientName").description("수령인 이름 오류 메시지"),
					),
				),
			)
	}

	private fun validRequest(body: String = VALID_BODY) = post("/api/orders")
		.header("Cookie", "JAPDA_ACCESS_TOKEN=<JWT>; XSRF-TOKEN=<CSRF>")
		.header("X-CSRF-TOKEN", "<CSRF>")
		.header("Idempotency-Key", IDEMPOTENCY_KEY)
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
		const val IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000"
		const val PAYMENT_ORDER_ID = "6f9619ff-8b86-4e7b-a273-63f667a76a88"
		val EXPIRES_AT: Instant = Instant.parse("2026-09-11T06:03:00Z")
		val VALID_BODY = """{"saleId":100,"quantity":2,"shippingAddress":{"recipientName":"홍길동","phoneNumber":"010-1234-5678","postalCode":"06236","address":"서울특별시 강남구 테헤란로 123","detailAddress":"101동 1001호","deliveryMessage":"문 앞"}}"""
		val EXPECTED_DTO = CreateOrderDto(
			123L,
			UUID.fromString(IDEMPOTENCY_KEY),
			100L,
			2,
			"홍길동",
			"010-1234-5678",
			"06236",
			"서울특별시 강남구 테헤란로 123",
			"101동 1001호",
			"문 앞",
		)
	}
}
