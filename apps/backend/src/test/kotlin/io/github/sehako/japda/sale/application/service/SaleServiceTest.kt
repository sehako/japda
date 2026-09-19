package io.github.sehako.japda.sale.application.service

import io.github.sehako.japda.order.domain.repository.SaleInventoryCounterRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryReserveResult
import io.github.sehako.japda.product.domain.model.Product
import io.github.sehako.japda.product.domain.repository.ProductRepository
import io.github.sehako.japda.sale.application.config.SaleDailyCapacity
import io.github.sehako.japda.sale.application.dto.CreateSaleDto
import io.github.sehako.japda.sale.domain.model.Sale
import io.github.sehako.japda.sale.domain.model.SaleDay
import io.github.sehako.japda.sale.domain.repository.SaleDayRepository
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import io.github.sehako.japda.sale.exception.SaleErrorCode
import io.github.sehako.japda.sale.exception.SaleException
import io.github.sehako.japda.sale.exception.SaleSellerAlreadyRegisteredPersistenceException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName

@DisplayName("판매 일정 서비스")
class SaleServiceTest {
    private val saleDate = LocalDate.parse("2026-09-12")
    private val now = Instant.parse("2026-09-11T00:00:01Z")

    @Test
    @DisplayName("READY 상태인 본인 상품을 등록하면 판매일 자리와 판매 일정을 저장한다")
    fun 준비된_본인_상품_등록_판매일_자리와_판매_일정을_저장한다() {
        val product = readyProduct(id = 10L, sellerId = 1L)
        val saleRepository = RecordingSaleRepository()
        val saleDayRepository = RecordingSaleDayRepository()
        val counterRepository = RecordingSaleInventoryCounterRepository()
        val service = service(product, saleRepository, saleDayRepository, counterRepository)

        val response = service.create(CreateSaleDto(1L, 10L, saleDate, 35_000L, 100))

        assertEquals(1, saleDayRepository.savedSaleDay?.registeredCount)
        assertEquals(10L, saleRepository.savedSale?.productId)
        assertEquals(1L, counterRepository.createdSaleId)
        assertEquals(now, counterRepository.createdAt)
        assertEquals(1L, response.id)
        assertEquals(Instant.parse("2026-09-11T15:00:00Z"), response.startsAt)
        assertEquals(Instant.parse("2026-09-12T15:00:00Z"), response.endsAt)
        assertEquals(now, response.createdAt)
    }

    @Test
    @DisplayName("다른 판매자의 상품은 없는 상품과 같은 오류로 거절한다")
    fun 다른_판매자_상품_등록_상품_없음으로_거절한다() {
        val service = service(readyProduct(id = 10L, sellerId = 2L))

        val exception = assertFailsWith<SaleException> {
            service.create(CreateSaleDto(1L, 10L, saleDate, 35_000L, 100))
        }

        assertEquals(SaleErrorCode.PRODUCT_NOT_FOUND, exception.errorCode)
    }

    @Test
    @DisplayName("DRAFT 상품은 판매 준비 전 오류로 거절한다")
    fun 준비되지_않은_상품_등록_판매_준비_전으로_거절한다() {
        val service = service(product(id = 10L, sellerId = 1L))

        val exception = assertFailsWith<SaleException> {
            service.create(CreateSaleDto(1L, 10L, saleDate, 35_000L, 100))
        }

        assertEquals(SaleErrorCode.PRODUCT_NOT_READY, exception.errorCode)
    }

    @Test
    @DisplayName("같은 판매자의 같은 판매일 등록은 한 자리도 추가 확보하지 않고 거절한다")
    fun 같은_판매자_같은_판매일_등록_자리_확보_없이_거절한다() {
        val saleRepository = RecordingSaleRepository(alreadyRegistered = true)
        val saleDayRepository = RecordingSaleDayRepository()
        val service = service(readyProduct(id = 10L, sellerId = 1L), saleRepository, saleDayRepository)

        val exception = assertFailsWith<SaleException> {
            service.create(CreateSaleDto(1L, 10L, saleDate, 35_000L, 100))
        }

        assertEquals(SaleErrorCode.SELLER_ALREADY_REGISTERED, exception.errorCode)
        assertEquals(0, saleDayRepository.lockedSaleDay.registeredCount)
    }

    @Test
    @DisplayName("transaction에서 판매자 중복 persistence 오류가 발생하면 판매자 중복 오류로 변환한다")
    fun transaction_판매자_중복_persistence_오류_판매자_중복_오류로_변환한다() {
        val saleRepository = RecordingSaleRepository(failWithSellerDuplicate = true)
        val service = service(readyProduct(id = 10L, sellerId = 1L), saleRepository)

        val exception = assertFailsWith<SaleException> {
            service.create(CreateSaleDto(1L, 10L, saleDate, 35_000L, 100))
        }

        assertEquals(SaleErrorCode.SELLER_ALREADY_REGISTERED, exception.errorCode)
    }

    private fun service(
        product: Product?,
        saleRepository: RecordingSaleRepository = RecordingSaleRepository(),
        saleDayRepository: RecordingSaleDayRepository = RecordingSaleDayRepository(),
        counterRepository: RecordingSaleInventoryCounterRepository = RecordingSaleInventoryCounterRepository(),
    ): SaleService {
        val transactionService = SaleRegistrationTransactionService(
            productRepository = StubProductRepository(product),
            saleRepository = saleRepository,
            saleDayRepository = saleDayRepository,
            saleInventoryCounterRepository = counterRepository,
            dailyCapacity = SaleDailyCapacity(20),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        return SaleService(transactionService)
    }

    private class RecordingSaleInventoryCounterRepository : SaleInventoryCounterRepository {
        var createdSaleId: Long? = null
        var createdAt: Instant? = null

        override fun create(saleId: Long, now: Instant) {
            createdSaleId = saleId
            createdAt = now
        }

        override fun reserve(saleId: Long, quantity: Int, now: Instant): SaleInventoryReserveResult =
            SaleInventoryReserveResult.Acquired

        override fun release(saleId: Long, quantity: Int, now: Instant): Boolean = true

        override fun findCommittedQuantity(saleId: Long): Int? = 0
    }

    private fun product(id: Long, sellerId: Long): Product = Product.create(
        sellerId = sellerId,
        name = "상품",
        description = null,
        createdAt = now,
    ).also { setId(it, id) }

    private fun readyProduct(id: Long, sellerId: Long): Product = product(id, sellerId).also(Product::markReady)

    private fun setId(product: Product, id: Long) {
        Product::class.java.getDeclaredField("id").apply {
            isAccessible = true
            set(product, id)
        }
    }

    private class StubProductRepository(private val product: Product?) : ProductRepository {
        override fun save(product: Product): Product = product
        override fun findById(id: Long): Product? = product
        override fun findByIdForUpdate(id: Long): Product? = product
        override fun findReadyProducts(query: io.github.sehako.japda.product.domain.repository.ReadyProductQuery) = emptyList<io.github.sehako.japda.product.domain.repository.ReadyProductSummary>()
    }

    private class RecordingSaleRepository(
        private val alreadyRegistered: Boolean = false,
        private val failWithSellerDuplicate: Boolean = false,
    ) : SaleRepository {
        var savedSale: Sale? = null

        override fun save(sale: Sale): Sale {
            if (failWithSellerDuplicate) {
                throw SaleSellerAlreadyRegisteredPersistenceException(IllegalStateException("중복"))
            }
            setId(sale, 1L)
            return sale.also { savedSale = it }
        }

        override fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate): Boolean = alreadyRegistered

        override fun findByIdForUpdate(id: Long): Sale? = null

        private fun setId(sale: Sale, id: Long) {
            Sale::class.java.getDeclaredField("id").apply {
                isAccessible = true
                set(sale, id)
            }
        }
    }

    private class RecordingSaleDayRepository : SaleDayRepository {
        val lockedSaleDay: SaleDay = SaleDay.create(LocalDate.parse("2026-09-12"), 20)
        var savedSaleDay: SaleDay? = null

        override fun createIfAbsent(saleDate: LocalDate, capacity: Int) = Unit
        override fun findBySaleDateForUpdate(saleDate: LocalDate): SaleDay? = lockedSaleDay
        override fun save(saleDay: SaleDay): SaleDay = saleDay.also { savedSaleDay = it }
    }
}
