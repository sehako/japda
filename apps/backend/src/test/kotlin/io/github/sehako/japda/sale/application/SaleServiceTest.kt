package io.github.sehako.japda.sale.application

import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.ProductStatus
import io.github.sehako.japda.sale.domain.Sale
import io.github.sehako.japda.sale.domain.SalePeriodConflictException
import io.github.sehako.japda.sale.domain.SaleRepository
import io.github.sehako.japda.sale.domain.SaleStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("판매 등록 서비스")
class SaleServiceTest {

	@Test
	@DisplayName("판매 등록_READY 상품을 잠금 조회하고 현재 시각으로 생성한 판매를 저장한다")
	fun 판매_등록_READY_상품을_잠금_조회하고_현재_시각으로_생성한_판매를_저장한다() {
		val productRepository = RecordingProductRepository(readyProduct())
		val saleRepository = RecordingSaleRepository(savedId = 41L)
		val clock = CountingClock(NOW)
		val service = SaleService(productRepository, saleRepository, clock)

		val response = service.create(validDto())

		assertEquals(listOf(PRODUCT_ID), productRepository.lockedProductIds)
		assertFalse(productRepository.generalLookupCalled)
		val savedSale = requireNotNull(saleRepository.receivedSale)
		assertEquals(PRODUCT_ID, savedSale.productId)
		assertEquals(10_000L, savedSale.price)
		assertEquals(100L, savedSale.initialQuantity)
		assertEquals(100L, savedSale.remainingQuantity)
		assertEquals(STARTS_AT, savedSale.startsAt)
		assertEquals(ENDS_AT, savedSale.endsAt)
		assertEquals(NOW, savedSale.createdAt)
		assertEquals(1, clock.instantCallCount)
		assertEquals(
			SaleResponse(
				id = 41L,
				productId = PRODUCT_ID,
				price = 10_000L,
				initialQuantity = 100L,
				remainingQuantity = 100L,
				startsAt = STARTS_AT,
				endsAt = ENDS_AT,
				status = SaleStatus.SCHEDULED,
				createdAt = NOW,
			),
			response,
		)
	}

	@Test
	@DisplayName("판매 등록_상품이 없거나 다른 판매자 소유이면 같은 찾을 수 없음 오류로 거절한다")
	fun 판매_등록_상품이_없거나_다른_판매자_소유이면_같은_찾을_수_없음_오류로_거절한다() {
		val missingSales = RecordingSaleRepository()
		val foreignSales = RecordingSaleRepository()
		val missingService = SaleService(RecordingProductRepository(null), missingSales, Clock.fixed(NOW, ZoneOffset.UTC))
		val foreignService = SaleService(
			RecordingProductRepository(product(sellerId = OTHER_SELLER_ID, status = ProductStatus.DRAFT)),
			foreignSales,
			Clock.fixed(NOW, ZoneOffset.UTC),
		)

		assertFailsWith<SaleTargetProductNotFoundException> { missingService.create(validDto()) }
		assertFailsWith<SaleTargetProductNotFoundException> { foreignService.create(validDto()) }
		assertEquals(null, missingSales.receivedSale)
		assertEquals(null, foreignSales.receivedSale)
	}

	@Test
	@DisplayName("판매 등록_소유한 DRAFT 상품이면 판매 준비 충돌로 거절한다")
	fun 판매_등록_소유한_DRAFT_상품이면_판매_준비_충돌로_거절한다() {
		val saleRepository = RecordingSaleRepository()
		val service = SaleService(
			RecordingProductRepository(product(status = ProductStatus.DRAFT)),
			saleRepository,
			Clock.fixed(NOW, ZoneOffset.UTC),
		)

		assertFailsWith<ProductNotReadyForSaleException> { service.create(validDto()) }
		assertEquals(null, saleRepository.receivedSale)
	}

	@Test
	@DisplayName("판매 등록_Repository의 판매 기간 충돌을 그대로 전달한다")
	fun 판매_등록_Repository의_판매_기간_충돌을_그대로_전달한다() {
		val conflict = SalePeriodConflictException()
		val service = SaleService(
			RecordingProductRepository(readyProduct()),
			ThrowingSaleRepository(conflict),
			Clock.fixed(NOW, ZoneOffset.UTC),
		)

		val thrown = assertFailsWith<SalePeriodConflictException> { service.create(validDto()) }

		assertSame(conflict, thrown)
	}

	private class RecordingProductRepository(private val product: Product?) : ProductRepository {
		val lockedProductIds = mutableListOf<Long>()
		var generalLookupCalled = false
			private set

		override fun save(product: Product): Product = product

		override fun findById(id: Long): Product? {
			generalLookupCalled = true
			return product
		}

		override fun findByIdForUpdate(id: Long): Product? {
			lockedProductIds += id
			return product
		}
	}

	private class RecordingSaleRepository(private val savedId: Long = 41L) : SaleRepository {
		var receivedSale: Sale? = null
			private set

		override fun save(sale: Sale): Sale {
			receivedSale = sale
			return Sale(
				id = savedId,
				productId = sale.productId,
				price = sale.price,
				initialQuantity = sale.initialQuantity,
				remainingQuantity = sale.remainingQuantity,
				startsAt = sale.startsAt,
				endsAt = sale.endsAt,
				createdAt = sale.createdAt,
			)
		}
	}

	private class ThrowingSaleRepository(private val conflict: SalePeriodConflictException) : SaleRepository {
		override fun save(sale: Sale): Sale = throw conflict
	}

	private class CountingClock(
		private val fixedInstant: Instant,
		private val zoneId: ZoneId = ZoneOffset.UTC,
	) : Clock() {
		var instantCallCount = 0
			private set

		override fun getZone(): ZoneId = zoneId

		override fun withZone(zone: ZoneId): Clock = CountingClock(fixedInstant, zone)

		override fun instant(): Instant {
			instantCallCount += 1
			return fixedInstant
		}
	}

	companion object {
		private const val PRODUCT_ID = 11L
		private const val SELLER_ID = 7L
		private const val OTHER_SELLER_ID = 8L
		private val NOW = Instant.parse("2026-09-09T04:00:00Z")
		private val STARTS_AT = Instant.parse("2026-09-09T05:00:00Z")
		private val ENDS_AT = Instant.parse("2026-09-16T05:00:00Z")

		private fun validDto() = CreateSaleDto(
			productId = PRODUCT_ID,
			sellerId = SELLER_ID,
			price = 10_000L,
			quantity = 100L,
			startsAt = STARTS_AT,
			endsAt = ENDS_AT,
		)

		private fun readyProduct(): Product = product().also { it.markReadyAfterImageRegistration() }

		private fun product(
			sellerId: Long = SELLER_ID,
			status: ProductStatus = ProductStatus.DRAFT,
		) = Product(
			id = PRODUCT_ID,
			sellerId = sellerId,
			name = "상품",
			description = "설명",
			status = status,
			createdAt = Instant.parse("2026-09-09T03:00:00Z"),
		)
	}
}
