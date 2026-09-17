package io.github.sehako.japda.order.application.inventory

import io.github.sehako.japda.order.domain.model.InventoryReservationStatus
import io.github.sehako.japda.order.domain.repository.InventoryReservationRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryCounterRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ExpiredInventoryReservationReleaseService(
	private val reservationRepository: InventoryReservationRepository,
	private val counterRepository: SaleInventoryCounterRepository,
) {
	fun releaseExpired(saleId: Long, now: Instant) {
		reservationRepository.findExpiredReservedBySaleId(saleId, now).forEach { reservation ->
			val released = reservationRepository.transitionById(
				reservation.id,
				InventoryReservationStatus.RESERVED,
				InventoryReservationStatus.RELEASED,
				now,
			)
			if (!released) return@forEach
			if (!counterRepository.release(saleId, reservation.quantity, now)) {
				logger.error(
					"재고 예약 반환 후 카운터 감소에 실패했습니다. saleId={}, orderId={}, reservationId={}",
					saleId,
					reservation.orderId,
					reservation.id,
				)
				throw IllegalStateException("재고 예약 반환 후 카운터 감소에 실패했습니다.")
			}
		}
	}

	private companion object {
		val logger = LoggerFactory.getLogger(ExpiredInventoryReservationReleaseService::class.java)
	}
}
