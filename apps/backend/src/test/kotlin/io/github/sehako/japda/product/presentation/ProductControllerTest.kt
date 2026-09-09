package io.github.sehako.japda.product.presentation

import io.github.sehako.japda.product.application.ProductService
import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

@DisplayName("상품 Controller")
class ProductControllerTest {

	private val now = Instant.parse("2026-09-09T03:00:00Z")

	@Test
	@DisplayName("상품 등록_201과 상대 Location 및 생성된 상품을 반환한다")
	fun 상품_등록_201과_상대_Location_및_생성된_상품을_반환한다() {
		val mockMvc = mockMvcWith(AssigningIdProductRepository())

		mockMvc.post("/api/products") {
			header("X-Seller-Id", "123")
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":" 상품 ","description":" 설명 "}"""
		}.andExpect {
			status { isCreated() }
			header { string("Location", "/api/products/1") }
			content { contentType(MediaType.APPLICATION_JSON) }
			jsonPath("$.id") { value(1) }
			jsonPath("$.sellerId") { value(123) }
			jsonPath("$.name") { value("상품") }
			jsonPath("$.description") { value("설명") }
			jsonPath("$.status") { value("DRAFT") }
			jsonPath("$.createdAt") { value("2026-09-09T03:00:00Z") }
		}
	}

	@Test
	@DisplayName("상품 등록_판매자 헤더가 없으면 필드 오류를 반환한다")
	fun 상품_등록_판매자_헤더가_없으면_필드_오류를_반환한다() {
		mockMvcWith(AssigningIdProductRepository()).post("/api/products") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"상품","description":"설명"}"""
		}.andExpect {
			status { isBadRequest() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.type") { value("about:blank") }
			jsonPath("$.title") { value("요청 값이 올바르지 않습니다.") }
			jsonPath("$.status") { value(400) }
			jsonPath("$.detail") { value("상품 등록 요청을 확인해 주세요.") }
			jsonPath("$.instance") { value("/api/products") }
			jsonPath("$.errors.sellerId") { value("판매자 ID는 필수입니다.") }
		}
	}

	@Test
	@DisplayName("상품 등록_올바르지 않은 판매자 헤더는 형식 오류를 반환한다")
	fun 상품_등록_올바르지_않은_판매자_헤더는_형식_오류를_반환한다() {
		listOf("판매자", "0", "-1").forEach { sellerId ->
			mockMvcWith(AssigningIdProductRepository()).post("/api/products") {
				header("X-Seller-Id", sellerId)
				contentType = MediaType.APPLICATION_JSON
				content = """{"name":"상품","description":"설명"}"""
			}.andExpect {
				status { isBadRequest() }
				jsonPath("$.errors.sellerId") { value("판매자 ID는 1 이상의 정수여야 합니다.") }
			}
		}
	}

	@Test
	@DisplayName("상품 등록_잘못된 상품 필드를 모두 반환한다")
	fun 상품_등록_잘못된_상품_필드를_모두_반환한다() {
		mockMvcWith(AssigningIdProductRepository()).post("/api/products") {
			header("X-Seller-Id", "123")
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":" ","description":null}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.errors.name") { value("상품명은 필수입니다.") }
			jsonPath("$.errors.description") { value("상품 설명은 필수입니다.") }
		}
	}

	@Test
	@DisplayName("상품 등록_상품 필드의 Unicode code point 최대 길이를 검증한다")
	fun 상품_등록_상품_필드의_Unicode_code_point_최대_길이를_검증한다() {
		val name = "😀".repeat(101)
		val description = "😀".repeat(5_001)

		mockMvcWith(AssigningIdProductRepository()).post("/api/products") {
			header("X-Seller-Id", "123")
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"$name","description":"$description"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.errors.name") { value("상품명은 100자 이하여야 합니다.") }
			jsonPath("$.errors.description") { value("상품 설명은 5,000자 이하여야 합니다.") }
		}
	}

	@Test
	@DisplayName("상품 등록_읽을 수 없는 본문은 요청 본문 오류를 반환한다")
	fun 상품_등록_읽을_수_없는_본문은_요청_본문_오류를_반환한다() {
		listOf("", "{", """{"name":123,"description":"설명"}""").forEach { body ->
			mockMvcWith(AssigningIdProductRepository()).post("/api/products") {
				header("X-Seller-Id", "123")
				contentType = MediaType.APPLICATION_JSON
				content = body
			}.andExpect {
				status { isBadRequest() }
				jsonPath("$.errors.request") { value("요청 본문을 읽을 수 없습니다.") }
			}
		}
	}

	@Test
	@DisplayName("상품 등록_알 수 없는 JSON 필드는 무시한다")
	fun 상품_등록_알_수_없는_JSON_필드는_무시한다() {
		mockMvcWith(AssigningIdProductRepository()).post("/api/products") {
			header("X-Seller-Id", "123")
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"상품","description":"설명","unknown":"값"}"""
		}.andExpect {
			status { isCreated() }
		}
	}

	@Test
	@DisplayName("상품 등록_예상하지 못한 오류는 내부 정보를 노출하지 않는다")
	fun 상품_등록_예상하지_못한_오류는_내부_정보를_노출하지_않는다() {
		mockMvcWith(FailingProductRepository()).post("/api/products") {
			header("X-Seller-Id", "123")
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"상품","description":"설명"}"""
		}.andExpect {
			status { isInternalServerError() }
			jsonPath("$.title") { value("서버 오류가 발생했습니다.") }
			jsonPath("$.detail") { value("요청 처리 중 오류가 발생했습니다.") }
			jsonPath("$.instance") { value("/api/products") }
			content { string(not(containsString("내부 SQL 상세 정보"))) }
		}
	}

	private fun mockMvcWith(repository: ProductRepository): MockMvc {
		val service = ProductService(repository, Clock.fixed(now, ZoneOffset.UTC))
		return MockMvcBuilders.standaloneSetup(ProductController(service, CreateProductRequestConverter()))
			.setControllerAdvice(ProductExceptionHandler())
			.build()
	}

	private class AssigningIdProductRepository : ProductRepository {
		override fun save(product: Product): Product = Product(
			id = 1L,
			sellerId = product.sellerId,
			name = product.name,
			description = product.description,
			status = product.status,
			createdAt = product.createdAt,
		)

		override fun findById(id: Long): Product? = null

		override fun findByIdForUpdate(id: Long): Product? = null
	}

	private class FailingProductRepository : ProductRepository {
		override fun save(product: Product): Product =
			throw IllegalStateException("내부 SQL 상세 정보")

		override fun findById(id: Long): Product? = null

		override fun findByIdForUpdate(id: Long): Product? = null
	}
}
