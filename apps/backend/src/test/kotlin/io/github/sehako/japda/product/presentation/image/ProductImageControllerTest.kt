package io.github.sehako.japda.product.presentation.image

import io.github.sehako.japda.global.error.GlobalExceptionHandler
import io.github.sehako.japda.global.error.ProblemDetailFactory
import io.github.sehako.japda.product.application.image.ProductImageRegistrationResponse
import io.github.sehako.japda.product.application.image.ProductImageRegistrationService
import io.github.sehako.japda.product.application.image.ProductImageResponse
import io.github.sehako.japda.product.application.image.RegisterProductImagesDto
import io.github.sehako.japda.product.domain.ProductStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.mock
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
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
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.partWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.request.RequestDocumentation.requestParts
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@DisplayName("판매자 상품 이미지 최초 등록 API")
@ExtendWith(RestDocumentationExtension::class)
class ProductImageControllerTest {
	private lateinit var service: ProductImageRegistrationService
	private lateinit var mockMvc: MockMvc
	private var capturedDto: RegisterProductImagesDto? = null
	private val successResponse = ProductImageRegistrationResponse(
		productId = 42L,
		status = ProductStatus.READY,
		images = listOf(
			ProductImageResponse(101L, 0, false),
			ProductImageResponse(102L, 1, true),
		),
	)

	@BeforeEach
	fun setUp(restDocumentation: RestDocumentationContextProvider) {
		capturedDto = null
		service = mock(ProductImageRegistrationService::class.java) { invocation ->
			if (invocation.method.name == "register") {
				capturedDto = invocation.arguments[0] as RegisterProductImagesDto
				successResponse
			} else {
				null
			}
		}
		mockMvc = MockMvcBuilders
			.standaloneSetup(ProductImageController(service))
			.setControllerAdvice(GlobalExceptionHandler(ProblemDetailFactory()))
			.apply<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(
				documentationConfiguration(restDocumentation),
			)
			.build()
	}

	@Test
	@DisplayName("파일 순서를 유지하고 대표 이미지를 지정해 201 응답을 반환한다")
	fun 유효한_요청_파일_순서를_유지하고_201을_반환한다() {
		mockMvc.perform(validRequest())
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.productId").value(42))
			.andExpect(jsonPath("$.status").value("READY"))
			.andExpect(jsonPath("$.images[0].id").value(101))
			.andExpect(jsonPath("$.images[0].displayOrder").value(0))
			.andExpect(jsonPath("$.images[0].isRepresentative").value(false))
			.andExpect(jsonPath("$.images[1].id").value(102))
			.andExpect(jsonPath("$.images[1].displayOrder").value(1))
			.andExpect(jsonPath("$.images[1].isRepresentative").value(true))
			.andDo(
				document(
					"product-image-register",
					preprocessRequest(prettyPrint()),
					preprocessResponse(prettyPrint()),
					requestHeaders(headerWithName("X-Seller-Id").description("임시 판매자 식별자")),
					pathParameters(parameterWithName("productId").description("상품 식별자")),
					requestParts(
						partWithName("files").description("수신 순서대로 등록할 이미지 파일 1~10개"),
						partWithName("representativeIndex").description("0부터 시작하는 대표 이미지 인덱스"),
					),
					responseFields(
						fieldWithPath("productId").description("상품 식별자"),
						fieldWithPath("status").description("이미지 등록 후 상품 상태"),
						fieldWithPath("images[].id").description("상품 이미지 식별자"),
						fieldWithPath("images[].displayOrder").description("0부터 시작하는 표시 순서"),
						fieldWithPath("images[].isRepresentative").description("대표 이미지 여부"),
					),
				),
			)

		val dto = requireNotNull(capturedDto)
		assertEquals(42L, dto.productId)
		assertEquals(1L, dto.sellerId)
		assertEquals(1, dto.representativeIndex)
		assertContentEquals(
			byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
			dto.files[0].openStream().use { it.readAllBytes() },
		)
		assertContentEquals(
			byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
			dto.files[1].openStream().use { it.readAllBytes() },
		)
	}

	@Test
	@DisplayName("대표 이미지 파트가 누락되면 대표 이미지 지정 오류를 반환한다")
	fun 대표_이미지_파트_누락_대표_이미지_지정_오류를_반환한다() {
		assertRepresentativeInvalid(
			multipart("/api/products/{productId}/images", 42L)
				.file(imageFile("files", "first.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
				.header("X-Seller-Id", "1"),
		)
			.andDo(
				document(
					"product-image-register-representative-invalid",
					preprocessRequest(prettyPrint()),
					preprocessResponse(prettyPrint()),
					responseFields(
						fieldWithPath("type").description("오류 유형 URI"),
						fieldWithPath("title").description("오류 제목"),
						fieldWithPath("status").description("HTTP 상태 코드"),
						fieldWithPath("detail").description("오류 설명"),
						fieldWithPath("instance").description("오류가 발생한 요청 경로"),
						fieldWithPath("code").description("안정적인 오류 코드"),
						fieldWithPath("errors.representativeIndex").description("대표 이미지 지정 오류 메시지"),
					),
				),
			)
	}

	@Test
	@DisplayName("대표 이미지 파트가 중복되면 대표 이미지 지정 오류를 반환한다")
	fun 대표_이미지_파트_중복_대표_이미지_지정_오류를_반환한다() {
		assertRepresentativeInvalid(
			multipart("/api/products/{productId}/images", 42L)
				.file(imageFile("files", "first.jpg", byteArrayOf(1)))
				.file(textPart("representativeIndex", "0"))
				.file(textPart("representativeIndex", "0"))
				.header("X-Seller-Id", "1"),
		)
	}

	@Test
	@DisplayName("대표 이미지 파트가 비었으면 대표 이미지 지정 오류를 반환한다")
	fun 대표_이미지_파트_빈값_대표_이미지_지정_오류를_반환한다() {
		assertRepresentativeInvalid(validRequest(representativeIndex = ""))
	}

	@Test
	@DisplayName("대표 이미지 파트가 정수가 아니면 대표 이미지 지정 오류를 반환한다")
	fun 대표_이미지_파트_비정수_대표_이미지_지정_오류를_반환한다() {
		assertRepresentativeInvalid(validRequest(representativeIndex = "first"))
	}

	@Test
	@DisplayName("상품 ID가 숫자가 아니면 공통 요청 파라미터 오류를 반환한다")
	fun 상품_ID_비정수_공통_요청_파라미터_오류를_반환한다() {
		mockMvc.perform(
			multipart("/api/products/{productId}/images", "product")
				.file(imageFile("files", "first.jpg", byteArrayOf(1)))
				.file(textPart("representativeIndex", "0"))
				.header("X-Seller-Id", "1"),
		)
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("COMMON_REQUEST_PARAMETER_INVALID"))
	}

	@Test
	@DisplayName("multipart 요청이 아니면 지원하지 않는 미디어 타입 오류를 반환한다")
	fun multipart가_아닌_요청_지원하지_않는_미디어_타입_오류를_반환한다() {
		mockMvc.perform(
			org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/products/42/images")
				.header("X-Seller-Id", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"),
		)
			.andExpect(status().isUnsupportedMediaType)
			.andExpect(jsonPath("$.code").value("COMMON_MEDIA_TYPE_UNSUPPORTED"))
	}

	private fun assertRepresentativeInvalid(
		request: org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder,
	): org.springframework.test.web.servlet.ResultActions =
		mockMvc.perform(request)
			.andExpect(status().isBadRequest)
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_REPRESENTATIVE_INVALID"))
			.andExpect(jsonPath("$.errors.representativeIndex").exists())

	private fun validRequest(representativeIndex: String = "1") =
		multipart("/api/products/{productId}/images", 42L)
			.file(imageFile("files", "first.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
			.file(imageFile("files", "second.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
			.file(textPart("representativeIndex", representativeIndex))
			.header("X-Seller-Id", "1")

	private fun imageFile(name: String, filename: String, content: ByteArray) =
		MockMultipartFile(name, filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content)

	private fun textPart(name: String, content: String) =
		MockMultipartFile(name, "", MediaType.TEXT_PLAIN_VALUE, content.toByteArray())
}
