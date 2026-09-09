package io.github.sehako.japda.sale.presentation

import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.ProductStatus
import io.github.sehako.japda.sale.application.SaleService
import io.github.sehako.japda.sale.domain.Sale
import io.github.sehako.japda.sale.domain.SalePeriodConflictException
import io.github.sehako.japda.sale.domain.SaleRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

@DisplayName("판매 Controller")
class SaleControllerTest {

	@Test
	@DisplayName("판매 등록_201과 상대 Location 및 생성된 판매를 반환한다")
	fun 판매_등록_201과_상대_Location_및_생성된_판매를_반환한다() {
		mockMvcWith().post("/api/products/11/sales") {
			header("X-Seller-Id", "7")
			contentType = MediaType.APPLICATION_JSON
			content = validBody()
		}.andExpect {
			status { isCreated() }
			header { string("Location", "/api/sales/41") }
			content { contentType(MediaType.APPLICATION_JSON) }
			jsonPath("$.id") { value(41) }
			jsonPath("$.productId") { value(11) }
			jsonPath("$.price") { value(10_000) }
			jsonPath("$.initialQuantity") { value(100) }
			jsonPath("$.remainingQuantity") { value(100) }
			jsonPath("$.startsAt") { value("2026-09-09T05:00:00Z") }
			jsonPath("$.endsAt") { value("2026-09-16T05:00:00Z") }
			jsonPath("$.status") { value("SCHEDULED") }
			jsonPath("$.createdAt") { value("2026-09-09T04:00:00Z") }
		}
	}

	@Test
	@DisplayName("판매 등록_잘못된 헤더와 경로 및 본문 값을 필드별 오류로 반환한다")
	fun 판매_등록_잘못된_헤더와_경로_및_본문_값을_필드별_오류로_반환한다() {
		mockMvcWith().post("/api/products/0/sales") {
			header("X-Seller-Id", "0")
			contentType = MediaType.APPLICATION_JSON
			content = """{"price":1.5,"quantity":"100","startsAt":"2026-09-09T05:00:00","endsAt":true}"""
		}.andExpect {
			status { isBadRequest() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.type") { value("about:blank") }
			jsonPath("$.title") { value("요청 값이 올바르지 않습니다.") }
			jsonPath("$.status") { value(400) }
			jsonPath("$.detail") { value("판매 등록 요청을 확인해 주세요.") }
			jsonPath("$.instance") { value("/api/products/0/sales") }
			jsonPath("$.errors.sellerId") { exists() }
			jsonPath("$.errors.productId") { exists() }
			jsonPath("$.errors.price") { exists() }
			jsonPath("$.errors.quantity") { exists() }
			jsonPath("$.errors.startsAt") { exists() }
			jsonPath("$.errors.endsAt") { exists() }
		}
	}

	@Test
	@DisplayName("판매 등록_읽을 수 없는 본문과 경로는 해당 필드 오류를 반환한다")
	fun 판매_등록_읽을_수_없는_본문과_경로는_해당_필드_오류를_반환한다() {
		mockMvcWith().post("/api/products/11/sales") {
			header("X-Seller-Id", "7")
			contentType = MediaType.APPLICATION_JSON
			content = "{"
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.errors.request") { value("요청 본문을 읽을 수 없습니다.") }
		}

		mockMvcWith().post("/api/products/not-a-number/sales") {
			header("X-Seller-Id", "7")
			contentType = MediaType.APPLICATION_JSON
			content = validBody()
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.errors.productId") { value("상품 ID는 1 이상의 정수여야 합니다.") }
		}
	}

	@Test
	@DisplayName("판매 등록_도메인 판매 값 오류를 필드 오류로 반환한다")
	fun 판매_등록_도메인_판매_값_오류를_필드_오류로_반환한다() {
		mockMvcWith().post("/api/products/11/sales") {
			header("X-Seller-Id", "7")
			contentType = MediaType.APPLICATION_JSON
			content = """{"price":10000,"quantity":100,"startsAt":"2026-09-16T05:00:00Z","endsAt":"2026-09-09T05:00:00Z"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.errors.endsAt") { value("판매 종료 시각은 판매 시작 시각보다 늦어야 합니다.") }
		}
	}

	@Test
	@DisplayName("판매 등록_상품 없음과 다른 판매자 상품은 같은 404를 반환한다")
	fun 판매_등록_상품_없음과_다른_판매자_상품은_같은_404를_반환한다() {
		listOf(null, product(sellerId = 8L)).forEach { targetProduct ->
			mockMvcWith(product = targetProduct).post("/api/products/11/sales") {
				header("X-Seller-Id", "7")
				contentType = MediaType.APPLICATION_JSON
				content = validBody()
			}.andExpect {
				status { isNotFound() }
				jsonPath("$.title") { value("상품을 찾을 수 없습니다.") }
				jsonPath("$.detail") { value("판매 대상 상품을 찾을 수 없습니다.") }
			}
		}
	}

	@Test
	@DisplayName("판매 등록_DRAFT 상품과 판매 기간 중첩을 구분된 409로 반환한다")
	fun 판매_등록_DRAFT_상품과_판매_기간_중첩을_구분된_409로_반환한다() {
		mockMvcWith(product = product(status = ProductStatus.DRAFT)).post("/api/products/11/sales") {
			header("X-Seller-Id", "7")
			contentType = MediaType.APPLICATION_JSON
			content = validBody()
		}.andExpect {
			status { isConflict() }
			jsonPath("$.title") { value("판매를 등록할 수 없습니다.") }
			jsonPath("$.detail") { value("상품의 판매 준비 상태를 확인해 주세요.") }
		}

		mockMvcWith(saleRepository = ConflictSaleRepository()).post("/api/products/11/sales") {
			header("X-Seller-Id", "7")
			contentType = MediaType.APPLICATION_JSON
			content = validBody()
		}.andExpect {
			status { isConflict() }
			jsonPath("$.title") { value("판매 기간이 겹칩니다.") }
			jsonPath("$.detail") { value("같은 상품의 기존 판매 기간을 확인해 주세요.") }
		}
	}

	@Test
	@DisplayName("판매 등록_예상하지 못한 오류는 내부 정보를 노출하지 않는다")
	fun 판매_등록_예상하지_못한_오류는_내부_정보를_노출하지_않는다() {
		mockMvcWith(saleRepository = FailingSaleRepository()).post("/api/products/11/sales") {
			header("X-Seller-Id", "7")
			contentType = MediaType.APPLICATION_JSON
			content = validBody()
		}.andExpect {
			status { isInternalServerError() }
			jsonPath("$.title") { value("서버 오류가 발생했습니다.") }
			jsonPath("$.detail") { value("요청 처리 중 오류가 발생했습니다.") }
			content { string(not(containsString("ex_sales_product_period 내부 SQL"))) }
		}
	}

	@Test
	@DisplayName("판매 API_알 수 없는 JSON 필드는 허용하고 지원하지 않는 메서드와 미디어 타입을 보존한다")
	fun 판매_API_알_수_없는_JSON_필드는_허용하고_지원하지_않는_메서드와_미디어_타입을_보존한다() {
		mockMvcWith().post("/api/products/11/sales") {
			header("X-Seller-Id", "7")
			contentType = MediaType.APPLICATION_JSON
			content = validBody().dropLast(1) + ",\"unknown\":\"값\"}"
		}.andExpect {
			status { isCreated() }
		}

		mockMvcWith().get("/api/products/11/sales").andExpect {
			status { isMethodNotAllowed() }
		}
		mockMvcWith().post("/api/products/11/sales") {
			header("X-Seller-Id", "7")
			contentType = MediaType.TEXT_PLAIN
			content = "판매"
		}.andExpect {
			status { isUnsupportedMediaType() }
		}
	}

	private fun mockMvcWith(
		product: Product? = product(),
		saleRepository: SaleRepository = AssigningIdSaleRepository(),
	): MockMvc {
		val service = SaleService(FixedProductRepository(product), saleRepository, Clock.fixed(NOW, ZoneOffset.UTC))
		return MockMvcBuilders.standaloneSetup(SaleController(service, CreateSaleRequestConverter()))
			.setControllerAdvice(SaleExceptionHandler())
			.build()
	}

	private class FixedProductRepository(private val product: Product?) : ProductRepository {
		override fun save(product: Product): Product = product
		override fun findById(id: Long): Product? = product
		override fun findByIdForUpdate(id: Long): Product? = product
	}

	private class AssigningIdSaleRepository : SaleRepository {
		override fun save(sale: Sale): Sale = Sale(
			id = 41L,
			productId = sale.productId,
			price = sale.price,
			initialQuantity = sale.initialQuantity,
			remainingQuantity = sale.remainingQuantity,
			startsAt = sale.startsAt,
			endsAt = sale.endsAt,
			createdAt = sale.createdAt,
		)
	}

	private class ConflictSaleRepository : SaleRepository {
		override fun save(sale: Sale): Sale = throw SalePeriodConflictException()
	}

	private class FailingSaleRepository : SaleRepository {
		override fun save(sale: Sale): Sale = throw IllegalStateException("ex_sales_product_period 내부 SQL")
	}

	companion object {
		private val NOW = Instant.parse("2026-09-09T04:00:00Z")

		private fun product(
			sellerId: Long = 7L,
			status: ProductStatus = ProductStatus.READY,
		) = Product(
			id = 11L,
			sellerId = sellerId,
			name = "상품",
			description = "설명",
			status = status,
			createdAt = Instant.parse("2026-09-09T03:00:00Z"),
		)

		private fun validBody() =
			"""{"price":10000,"quantity":100,"startsAt":"2026-09-09T05:00:00Z","endsAt":"2026-09-16T05:00:00Z"}"""
	}
}
