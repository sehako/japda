package io.github.sehako.japda.sale.infrastructure

import io.github.sehako.japda.PostgreSqlTestContainerConfiguration
import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.sale.domain.Sale
import io.github.sehako.japda.sale.domain.SalePeriodConflictException
import io.github.sehako.japda.sale.domain.SaleRepository
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Import(PostgreSqlTestContainerConfiguration::class)
@Transactional
@DisplayName("판매 Repository 구현체")
class SaleRepositoryImplTest(
	@Autowired private val saleRepository: SaleRepository,
	@Autowired private val productRepository: ProductRepository,
	@Autowired private val entityManager: EntityManager,
) {
	@Test
	@DisplayName("판매 저장_save 호출 안에서 flush하고 모든 필드를 보존한다")
	fun 판매_저장_save_호출_안에서_flush하고_모든_필드를_보존한다() {
		val productId = createProduct()
		val startsAt = Instant.parse("2026-09-09T09:00:00Z")
		val endsAt = Instant.parse("2026-09-16T09:00:00Z")
		val createdAt = Instant.parse("2026-09-09T08:00:00Z")

		val saved = saleRepository.save(
			Sale.create(productId, 10_000L, 100L, startsAt, endsAt, createdAt),
		)
		val id = assertNotNull(saved.id)
		entityManager.clear()

		val found = entityManager.find(Sale::class.java, id)

		assertEquals(productId, found.productId)
		assertEquals(10_000L, found.price)
		assertEquals(100L, found.initialQuantity)
		assertEquals(100L, found.remainingQuantity)
		assertEquals(startsAt, found.startsAt)
		assertEquals(endsAt, found.endsAt)
		assertEquals(createdAt, found.createdAt)
	}

	@Test
	@DisplayName("판매 저장_판매 기간 exclusion constraint 위반만 도메인 충돌로 변환한다")
	fun 판매_저장_판매_기간_exclusion_constraint_위반만_도메인_충돌로_변환한다() {
		val productId = createProduct()
		val createdAt = Instant.parse("2026-09-09T08:00:00Z")
		saleRepository.save(
			Sale.create(
				productId,
				10_000L,
				100L,
				Instant.parse("2026-09-09T09:00:00Z"),
				Instant.parse("2026-09-16T09:00:00Z"),
				createdAt,
			),
		)

		assertFailsWith<SalePeriodConflictException> {
			saleRepository.save(
				Sale.create(
					productId,
					20_000L,
					50L,
					Instant.parse("2026-09-10T09:00:00Z"),
					Instant.parse("2026-09-17T09:00:00Z"),
					createdAt,
				),
			)
		}
	}

	@Test
	@DisplayName("판매 저장_다른 DB 무결성 오류는 기간 충돌로 변환하지 않는다")
	fun 판매_저장_다른_DB_무결성_오류는_기간_충돌로_변환하지_않는다() {
		val sale = Sale.create(
			Long.MAX_VALUE,
			10_000L,
			100L,
			Instant.parse("2026-09-09T09:00:00Z"),
			Instant.parse("2026-09-16T09:00:00Z"),
			Instant.parse("2026-09-09T08:00:00Z"),
		)

		assertFailsWith<DataIntegrityViolationException> {
			saleRepository.save(sale)
		}
	}

	private fun createProduct(): Long {
		val product = productRepository.save(
			Product.create(
				123L,
				"상품",
				"설명",
				Instant.parse("2026-09-09T07:00:00Z"),
			),
		)
		return assertNotNull(product.id)
	}
}
