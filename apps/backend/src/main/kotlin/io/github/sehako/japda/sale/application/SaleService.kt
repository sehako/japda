package io.github.sehako.japda.sale.application

import io.github.sehako.japda.sale.domain.BuyerSaleProductQueryRepository
import io.github.sehako.japda.sale.domain.BuyerSaleProductQueryResult
import io.github.sehako.japda.sale.domain.Sale
import io.github.sehako.japda.sale.exception.SaleErrorCode
import io.github.sehako.japda.sale.exception.SaleException
import io.github.sehako.japda.sale.exception.SaleSellerAlreadyRegisteredPersistenceException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SaleService(
	private val transactionService: SaleRegistrationTransactionService? = null,
	private val buyerSaleProductQueryRepository: BuyerSaleProductQueryRepository? = null,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun create(dto: CreateSaleDto): SaleResponse = try {
		requireNotNull(transactionService).register(dto)
	} catch (_: SaleSellerAlreadyRegisteredPersistenceException) {
		throw SaleException(SaleErrorCode.SELLER_ALREADY_REGISTERED)
	}

	@Transactional(readOnly = true)
	fun findBuyerSaleProducts(saleDate: LocalDate): BuyerSaleProductListResponse {
		val now = clock.instant()
		val latestDate = now.atZone(SALE_ZONE).toLocalDate().plusDays(1)
		if (saleDate > latestDate) throw SaleException(SaleErrorCode.DATE_OUT_OF_RANGE)

		val sales = requireNotNull(buyerSaleProductQueryRepository)
			.findAllBySaleDate(saleDate)
			.map { it.toResponse(now) }
		return BuyerSaleProductListResponse(sales)
	}

	private fun BuyerSaleProductQueryResult.toResponse(now: Instant): BuyerSaleProductResponse {
		val startsAt = Sale.startsAt(saleDate)
		val endsAt = Sale.endsAt(saleDate)
		val status = when {
			now < startsAt -> BuyerSaleStatus.UPCOMING
			now < endsAt -> BuyerSaleStatus.ON_SALE
			else -> BuyerSaleStatus.ENDED
		}
		return BuyerSaleProductResponse(
			saleId = saleId,
			productId = productId,
			name = name,
			description = description,
			price = price,
			quantity = quantity,
			saleDate = saleDate,
			startsAt = startsAt,
			endsAt = endsAt,
			status = status,
			representativeImagePath = "/$representativeImageObjectKey",
		)
	}

	companion object {
		private val SALE_ZONE = ZoneId.of("Asia/Seoul")
	}
}
