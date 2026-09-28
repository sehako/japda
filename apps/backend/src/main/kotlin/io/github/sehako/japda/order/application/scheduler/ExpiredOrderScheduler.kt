package io.github.sehako.japda.order.application.scheduler

import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import java.time.Clock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ExpiredOrderScheduler(
	private val orderRepository: OrderRepository,
	private val saleRepository: SaleRepository,
	private val clock: Clock,
) {
	@Scheduled(fixedDelayString = "\${order.expiration-interval:PT30S}")
	@Transactional
	fun expire() {
		val now = clock.instant()
		orderRepository.findExpiredPendingWithoutPayment(now).forEach { order ->
			if (!orderRepository.markExpiredIfPendingWithoutPayment(requireNotNull(order.id), now)) return@forEach
			check(saleRepository.decreaseCommittedQuantity(order.saleId, order.quantity)) {
				"만료 주문의 판매 수량 반환에 실패했습니다."
			}
		}
	}
}
