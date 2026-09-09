package io.github.sehako.japda.product.presentation.image

import io.github.sehako.japda.product.presentation.ProductExceptionHandler
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import io.github.sehako.japda.product.application.image.ProductImageFileValidator
import io.github.sehako.japda.product.application.image.ProductImageObjectKeyGenerator
import io.github.sehako.japda.product.application.image.ProductImagePayloadTooLargeException
import io.github.sehako.japda.product.application.image.ProductImageRegistrationService
import io.github.sehako.japda.product.application.image.ProductImageResponse
import io.github.sehako.japda.product.application.image.ProductImageService
import io.github.sehako.japda.product.application.image.ProductImageUploadResponse
import io.github.sehako.japda.product.application.image.ProductNotFoundException
import io.github.sehako.japda.product.application.image.UploadProductImagesDto
import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.image.ProductImage
import io.github.sehako.japda.product.domain.image.ProductImageContent
import io.github.sehako.japda.product.domain.image.ProductImageRegistrationConflictException
import io.github.sehako.japda.product.domain.image.ProductImageRepository
import io.github.sehako.japda.product.domain.image.ProductImageStorage
import io.github.sehako.japda.product.domain.image.ProductImageStorageException
import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.ProductStatus
import java.time.Clock
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.setup.MockMvcBuilders

@DisplayName("상품 이미지 Controller")
class ProductImageControllerTest {

	@Test
	@DisplayName("이미지 업로드_201과 READY 상품 이미지 응답을 반환하고 multipart 순서를 보존한다")
	fun 이미지_업로드_201과_READY_상품_이미지_응답을_반환하고_multipart_순서를_보존한다() {
		val service = RecordingProductImageService(result = successResponse())
		val first = MockMultipartFile("files", "front.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
		val second = MockMultipartFile("files", "side.webp", "image/webp", byteArrayOf(4, 5))

		mockMvcWith(service).multipart("/api/products/123/images") {
			header("X-Seller-Id", "456")
			file(first)
			file(second)
			param("representativeIndex", "1")
		}.andExpect {
			status { isCreated() }
			content { contentType(MediaType.APPLICATION_JSON) }
			jsonPath("$.productId") { value(123) }
			jsonPath("$.status") { value("READY") }
			jsonPath("$.images[0].id") { value(101) }
			jsonPath("$.images[0].sortOrder") { value(0) }
			jsonPath("$.images[0].representative") { value(false) }
			jsonPath("$.images[1].representative") { value(true) }
			jsonPath("$.images[1].contentType") { value("image/webp") }
			jsonPath("$.images[1].sizeBytes") { value(2) }
		}

		val dto = requireNotNull(service.received)
		assertEquals(listOf("image/jpeg", "image/webp"), dto.files.map { it.contentType })
		assertContentEquals(byteArrayOf(1, 2, 3), dto.files[0].openStream().use { it.readAllBytes() })
		assertContentEquals(byteArrayOf(1, 2, 3), dto.files[0].openStream().use { it.readAllBytes() })
	}

	@Test
	@DisplayName("이미지 업로드_잘못된 입력은 필드 오류가 있는 400을 반환한다")
	fun 이미지_업로드_잘못된_입력은_필드_오류가_있는_400을_반환한다() {
		mockMvcWith(RecordingProductImageService(result = successResponse()))
			.multipart("/api/products/0/images") {
				header("X-Seller-Id", "-1")
				param("representativeIndex", "대표")
			}.andExpect {
				status { isBadRequest() }
				content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
				jsonPath("$.status") { value(400) }
				jsonPath("$.instance") { value("/api/products/0/images") }
				jsonPath("$.errors.productId") { exists() }
				jsonPath("$.errors.sellerId") { exists() }
				jsonPath("$.errors.representativeIndex") { exists() }
			}
	}

	@Test
	@DisplayName("이미지 업로드_Application 오류를 합의된 ProblemDetail 상태로 변환한다")
	fun 이미지_업로드_Application_오류를_합의된_ProblemDetail_상태로_변환한다() {
		val scenarios = listOf(
			ProductNotFoundException() to 404,
			ProductImageRegistrationConflictException() to 409,
			ProductImagePayloadTooLargeException(mapOf("files" to "내부 제한")) to 413,
			ProductImageStorageException(IllegalStateException("민감한 S3 응답")) to 503,
			IllegalStateException("민감한 SQL 응답") to 500,
		)

		scenarios.forEach { (failure, status) ->
			mockMvcWith(RecordingProductImageService(failure = failure)).validUpload().andExpect {
				status { isEqualTo(status) }
				content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
				jsonPath("$.status") { value(status) }
				jsonPath("$.instance") { value("/api/products/123/images") }
				content { string(not(containsString("민감한"))) }
			}
		}
	}

	@Test
	@DisplayName("이미지 업로드_저장소 오류 로그에 S3 내부 응답을 노출하지 않는다")
	fun 이미지_업로드_저장소_오류_로그에_S3_내부_응답을_노출하지_않는다() {
		val logger = LoggerFactory.getLogger(ProductExceptionHandler::class.java) as Logger
		val appender = ListAppender<ILoggingEvent>().also { it.start() }
		logger.addAppender(appender)
		try {
			val failure = ProductImageStorageException(IllegalStateException("민감한 S3 내부 응답"))

			mockMvcWith(RecordingProductImageService(failure = failure)).validUpload().andExpect {
				status { isServiceUnavailable() }
			}

			val renderedLogs = appender.list.joinToString("\n") { event ->
				buildString {
					append(event.formattedMessage)
					event.throwableProxy?.let { append(ThrowableProxyUtil.asString(it)) }
				}
			}
			assertEquals(false, renderedLogs.contains("민감한 S3 내부 응답"))
		} finally {
			logger.detachAppender(appender)
		}
	}

	private fun MockMvc.validUpload() = multipart("/api/products/123/images") {
		header("X-Seller-Id", "456")
		file(MockMultipartFile("files", "front.jpg", "image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1)))
		param("representativeIndex", "0")
	}

	private fun mockMvcWith(service: ProductImageService): MockMvc =
		MockMvcBuilders.standaloneSetup(ProductImageController(service, UploadProductImagesRequestConverter()))
			.setControllerAdvice(ProductExceptionHandler(), ProductImageMultipartExceptionHandler())
			.build()

	private fun successResponse() = ProductImageUploadResponse(
		productId = 123L,
		status = ProductStatus.READY,
		images = listOf(
			ProductImageResponse(101L, 0, false, "image/jpeg", 3L),
			ProductImageResponse(102L, 1, true, "image/webp", 2L),
		),
	)

	private class RecordingProductImageService(
		private val result: ProductImageUploadResponse? = null,
		private val failure: RuntimeException? = null,
	) : ProductImageService(
		EmptyProductRepository,
		EmptyProductImageRepository,
		ProductImageFileValidator(),
		ProductImageObjectKeyGenerator("images") { UUID.randomUUID() },
		EmptyProductImageStorage,
		ProductImageRegistrationService(EmptyProductRepository, EmptyProductImageRepository, Clock.systemUTC()),
	) {
		var received: UploadProductImagesDto? = null

		override fun upload(dto: UploadProductImagesDto): ProductImageUploadResponse {
			received = dto
			failure?.let { throw it }
			return requireNotNull(result)
		}
	}

	private object EmptyProductRepository : ProductRepository {
		override fun save(product: Product): Product = product
		override fun findById(id: Long): Product? = null
		override fun findByIdForUpdate(id: Long): Product? = null
	}

	private object EmptyProductImageRepository : ProductImageRepository {
		override fun existsByProductId(productId: Long): Boolean = false
		override fun saveAll(images: List<ProductImage>): List<ProductImage> = images
		override fun findAllByProductIdOrderBySortOrder(productId: Long): List<ProductImage> = emptyList()
	}

	private object EmptyProductImageStorage : ProductImageStorage {
		override fun store(objectKey: String, content: ProductImageContent) = Unit
		override fun delete(objectKey: String) = Unit
		override fun markForCleanup(objectKey: String) = Unit
	}
}
