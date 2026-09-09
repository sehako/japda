package io.github.sehako.japda.sale.application

import io.github.sehako.japda.sale.domain.Sale
import io.github.sehako.japda.sale.domain.SaleStatus
import java.time.Instant

data class SaleResponse(
	val id: Long,
	val productId: Long,
	val price: Long,
	val initialQuantity: Long,
	val remainingQuantity: Long,
	val startsAt: Instant,
	val endsAt: Instant,
	val status: SaleStatus,
	val createdAt: Instant,
) {

	companion object {
		fun from(sale: Sale, now: Instant): SaleResponse = SaleResponse(
			id = checkNotNull(sale.id) { "저장된 판매 정보에는 ID가 있어야 합니다." },
			productId = sale.productId,
			price = sale.price,
			initialQuantity = sale.initialQuantity,
			remainingQuantity = sale.remainingQuantity,
			startsAt = sale.startsAt,
			endsAt = sale.endsAt,
			status = sale.statusAt(now),
			createdAt = sale.createdAt,
		)
	}
}
