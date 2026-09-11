package io.github.sehako.japda.product.application.service

import io.github.sehako.japda.product.application.cursor.ReadyProductCursorCodec
import io.github.sehako.japda.product.application.dto.ListReadyProductsDto
import io.github.sehako.japda.product.application.response.ReadyProductResponse
import io.github.sehako.japda.product.domain.model.Product
import io.github.sehako.japda.product.domain.repository.ProductRepository
import io.github.sehako.japda.product.domain.repository.ReadyProductCursorBoundary
import io.github.sehako.japda.product.domain.repository.ReadyProductQuery
import io.github.sehako.japda.product.domain.repository.ReadyProductSort
import io.github.sehako.japda.product.domain.repository.ReadyProductSummary
import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName
import tools.jackson.databind.json.JsonMapper

@DisplayName("판매 준비 완료 상품 목록 서비스")
class ReadyProductListServiceTest {
	private val codec = ReadyProductCursorCodec(JsonMapper.builder().build())

	@Test
	@DisplayName("정렬과 size + 1 조회 조건으로 다음 커서가 있는 페이지를 조립한다")
	fun 준비_상품_목록_size_보다_많으면_다음_커서를_반환한다() {
		val repository = RecordingProductRepository(
			listOf(ReadyProductSummary(9L, "가"), ReadyProductSummary(7L, "나"), ReadyProductSummary(5L, "다")),
		)
		val service = ProductService(repository, Clock.systemUTC(), codec)

		val response = service.listReady(ListReadyProductsDto(3L, "latest", null, 2))

		assertEquals(listOf(ReadyProductResponse(9L, "가"), ReadyProductResponse(7L, "나")), response.items)
		assertEquals(ReadyProductCursorBoundary.Id(7L), codec.decode(response.nextCursor!!, ReadyProductSort.LATEST))
		assertEquals(ReadyProductQuery(3L, ReadyProductSort.LATEST, null, 3), repository.query)
	}

	@Test
	@DisplayName("상품명 커서를 복원해 복합 경계로 조회한다")
	fun 상품명_커서_복원해_복합_경계로_조회한다() {
		val repository = RecordingProductRepository(emptyList())
		val service = ProductService(repository, Clock.systemUTC(), codec)
		val cursor = codec.encode(ReadyProductSort.NAME_DESC, ReadyProductSummary(11L, "콜라보"))

		val response = service.listReady(ListReadyProductsDto(3L, "name-desc", cursor, 100))

		assertEquals(ReadyProductQuery(3L, ReadyProductSort.NAME_DESC, ReadyProductCursorBoundary.Name("콜라보", 11L), 101), repository.query)
		assertEquals(emptyList(), response.items)
		assertNull(response.nextCursor)
	}

	@Test
	@DisplayName("조회 결과가 페이지 크기 이하이면 다음 커서를 반환하지 않는다")
	fun 준비_상품_목록_마지막_페이지면_다음_커서가_없다() {
		val repository = RecordingProductRepository(listOf(ReadyProductSummary(1L, "상품")))
		val response = ProductService(repository, Clock.systemUTC(), codec)
			.listReady(ListReadyProductsDto(1L, "oldest", null, 2))

		assertEquals(listOf(ReadyProductResponse(1L, "상품")), response.items)
		assertNull(response.nextCursor)
	}

	@Test
	@DisplayName("잘못된 판매자 식별자, 정렬과 페이지 크기를 각각 거부한다")
	fun 잘못된_목록_입력_거부한다() {
		val service = ProductService(RecordingProductRepository(emptyList()), Clock.systemUTC(), codec)
		val cases = listOf(
			ListReadyProductsDto(0L, "latest", null, 20) to ProductErrorCode.SELLER_ID_INVALID,
			ListReadyProductsDto(1L, "newest", null, 20) to ProductErrorCode.SORT_INVALID,
			ListReadyProductsDto(1L, "latest", null, 0) to ProductErrorCode.PAGE_SIZE_INVALID,
			ListReadyProductsDto(1L, "latest", null, 101) to ProductErrorCode.PAGE_SIZE_INVALID,
		)

		cases.forEach { (dto, errorCode) ->
			val exception = assertFailsWith<ProductException> { service.listReady(dto) }
			assertEquals(errorCode, exception.errorCode)
		}
	}

	private class RecordingProductRepository(
		private val result: List<ReadyProductSummary>,
	) : ProductRepository {
		var query: ReadyProductQuery? = null

		override fun save(product: Product): Product = product
		override fun findById(id: Long): Product? = null
		override fun findByIdForUpdate(id: Long): Product? = null
		override fun findReadyProducts(query: ReadyProductQuery): List<ReadyProductSummary> {
			this.query = query
			return result
		}
	}
}
