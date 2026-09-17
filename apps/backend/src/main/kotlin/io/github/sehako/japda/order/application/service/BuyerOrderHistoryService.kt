package io.github.sehako.japda.order.application.service

import io.github.sehako.japda.order.application.cursor.OrderCursorCodec
import io.github.sehako.japda.order.application.dto.ListBuyerOrdersDto
import io.github.sehako.japda.order.application.response.BuyerOrderPageResponse
import io.github.sehako.japda.order.application.response.BuyerOrderResponse
import io.github.sehako.japda.order.domain.repository.BuyerOrderCursorBoundary
import io.github.sehako.japda.order.domain.repository.BuyerOrderQuery
import io.github.sehako.japda.order.domain.repository.BuyerOrderQueryRepository
import io.github.sehako.japda.order.exception.OrderErrorCode
import io.github.sehako.japda.order.exception.OrderException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BuyerOrderHistoryService(
	private val repository: BuyerOrderQueryRepository,
	private val cursorCodec: OrderCursorCodec,
) {
	@Transactional(readOnly = true)
	fun list(dto: ListBuyerOrdersDto): BuyerOrderPageResponse {
		if (dto.size !in MIN_PAGE_SIZE..MAX_PAGE_SIZE) throw OrderException(OrderErrorCode.PAGE_SIZE_INVALID)
		val boundary = dto.cursor?.let(cursorCodec::decode)
		val orders = repository.findAll(BuyerOrderQuery(dto.buyerId, boundary, dto.size + 1))
		val hasNext = orders.size > dto.size
		val pageItems = if (hasNext) orders.take(dto.size) else orders
		val nextCursor = if (hasNext) {
			pageItems.last().let { cursorCodec.encode(BuyerOrderCursorBoundary(it.createdAt, it.orderId)) }
		} else {
			null
		}
		return BuyerOrderPageResponse(
			items = pageItems.map {
				BuyerOrderResponse(
					it.orderId,
					it.status,
					it.productName,
					it.quantity,
					it.unitPrice,
					it.totalPrice,
					it.createdAt,
					it.expiresAt,
				)
			},
			nextCursor = nextCursor,
		)
	}

	private companion object {
		const val MIN_PAGE_SIZE = 1
		const val MAX_PAGE_SIZE = 100
	}
}
