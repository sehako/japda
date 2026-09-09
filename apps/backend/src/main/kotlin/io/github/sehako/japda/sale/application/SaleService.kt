package io.github.sehako.japda.sale.application

import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.ProductStatus
import io.github.sehako.japda.sale.domain.Sale
import io.github.sehako.japda.sale.domain.SaleRepository
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SaleService(
	private val productRepository: ProductRepository,
	private val saleRepository: SaleRepository,
	private val clock: Clock,
) {

	@Transactional
	fun create(dto: CreateSaleDto): SaleResponse {
		val now = clock.instant()
		val product = productRepository.findByIdForUpdate(dto.productId)
			?: throw SaleTargetProductNotFoundException()

		if (product.sellerId != dto.sellerId) {
			throw SaleTargetProductNotFoundException()
		}
		if (product.status != ProductStatus.READY) {
			throw ProductNotReadyForSaleException()
		}

		val sale = Sale.create(
			productId = dto.productId,
			price = dto.price,
			quantity = dto.quantity,
			startsAt = dto.startsAt,
			endsAt = dto.endsAt,
			now = now,
		)

		return SaleResponse.from(saleRepository.save(sale), now)
	}
}
