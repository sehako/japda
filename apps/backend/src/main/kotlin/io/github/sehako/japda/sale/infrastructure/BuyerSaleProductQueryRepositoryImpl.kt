package io.github.sehako.japda.sale.infrastructure

import io.github.sehako.japda.sale.domain.BuyerSaleProductQueryRepository
import io.github.sehako.japda.sale.domain.BuyerSaleProductDetailImageQueryResult
import io.github.sehako.japda.sale.domain.BuyerSaleProductDetailQueryResult
import io.github.sehako.japda.sale.domain.BuyerSaleProductQueryResult
import java.time.LocalDate
import org.springframework.stereotype.Repository

@Repository
internal class BuyerSaleProductQueryRepositoryImpl(
	private val buyerSaleProductJpaRepository: BuyerSaleProductJpaRepository,
) : BuyerSaleProductQueryRepository {
	override fun findDetailBySaleId(saleId: Long): BuyerSaleProductDetailQueryResult? {
		val projections = buyerSaleProductJpaRepository.findDetailProjectionsBySaleId(saleId)
		val first = projections.firstOrNull() ?: return null
		return BuyerSaleProductDetailQueryResult(
			saleId = first.saleId,
			productId = first.productId,
			name = first.name,
			description = first.description,
			price = first.price,
			quantity = first.quantity,
			saleDate = first.saleDate,
			images = projections.map {
				BuyerSaleProductDetailImageQueryResult(
					objectKey = it.imageObjectKey,
					displayOrder = it.imageDisplayOrder,
					isRepresentative = it.imageRepresentative,
				)
			},
		)
	}

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
