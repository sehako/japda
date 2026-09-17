package io.github.sehako.japda.order.domain.repository

interface BuyerOrderQueryRepository {
	fun findAll(query: BuyerOrderQuery): List<BuyerOrderSummary>
}
