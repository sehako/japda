package io.github.sehako.japda.sale.application.service

import io.github.sehako.japda.sale.application.config.SaleDailyCapacity
import io.github.sehako.japda.sale.application.dto.CreateSaleDto
import io.github.sehako.japda.sale.application.response.SaleResponse
import io.github.sehako.japda.product.domain.repository.ProductRepository
import io.github.sehako.japda.product.domain.model.ProductStatus
import io.github.sehako.japda.sale.domain.model.Sale
import io.github.sehako.japda.sale.domain.repository.SaleDayRepository
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import io.github.sehako.japda.sale.exception.SaleErrorCode
import io.github.sehako.japda.sale.exception.SaleException
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SaleRegistrationTransactionService(
	private val productRepository: ProductRepository,
	private val saleRepository: SaleRepository,
	private val saleDayRepository: SaleDayRepository,
	private val dailyCapacity: SaleDailyCapacity,
	private val clock: Clock,
) {
	@Transactional
	fun register(dto: CreateSaleDto): SaleResponse {
		val sale = Sale.create(
			productId = dto.productId,
			sellerId = dto.sellerId,
			saleDate = dto.saleDate,
			price = dto.price,
			quantity = dto.quantity,
			createdAt = clock.instant(),
		)

		saleDayRepository.createIfAbsent(sale.saleDate, dailyCapacity.value)
		val saleDay = checkNotNull(saleDayRepository.findBySaleDateForUpdate(sale.saleDate))
		Sale.validateRegistrationTime(sale.saleDate, clock.instant())

		val product = productRepository.findById(sale.productId)
		if (product == null || product.sellerId != sale.sellerId) {
			throw SaleException(SaleErrorCode.PRODUCT_NOT_FOUND)
		}
		if (product.status != ProductStatus.READY) {
			throw SaleException(SaleErrorCode.PRODUCT_NOT_READY)
		}
		if (saleRepository.existsBySellerIdAndSaleDate(sale.sellerId, sale.saleDate)) {
			throw SaleException(SaleErrorCode.SELLER_ALREADY_REGISTERED)
		}

		saleDay.reserve()
		saleDayRepository.save(saleDay)
		return saleRepository.save(sale).toResponse()
	}

	private fun Sale.toResponse(): SaleResponse = SaleResponse(
		id = requireNotNull(id),
		productId = productId,
		sellerId = sellerId,
		saleDate = saleDate,
		price = price,
		quantity = quantity,
		startsAt = startsAt,
		endsAt = endsAt,
		createdAt = createdAt,
	)
}
