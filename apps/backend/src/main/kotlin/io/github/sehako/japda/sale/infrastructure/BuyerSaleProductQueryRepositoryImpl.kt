package io.github.sehako.japda.sale.infrastructure

import io.github.sehako.japda.sale.domain.BuyerSaleProductQueryRepository
import io.github.sehako.japda.sale.domain.BuyerSaleProductQueryResult
import java.time.LocalDate
import org.springframework.stereotype.Repository

@Repository
internal class BuyerSaleProductQueryRepositoryImpl(
	private val buyerSaleProductJpaRepository: BuyerSaleProductJpaRepository,
) : BuyerSaleProductQueryRepository {
	override fun findAllBySaleDate(saleDate: LocalDate): List<BuyerSaleProductQueryResult> =
		buyerSaleProductJpaRepository.findProjectionsBySaleDate(saleDate).map { projection ->
			BuyerSaleProductQueryResult(
				saleId = projection.saleId,
				productId = projection.productId,
				name = projection.name,
				description = projection.description,
				price = projection.price,
				quantity = projection.quantity,
				saleDate = projection.saleDate,
				createdAt = projection.createdAt,
				representativeImageObjectKey = projection.representativeImageObjectKey,
			)
		}
}
