package io.github.sehako.japda.order.application.inventory.snapshot

import io.github.sehako.japda.order.application.inventory.ExpiredInventoryReservationReleaseService
import io.github.sehako.japda.order.domain.repository.SaleInventoryCounterRepository
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class InventorySnapshotService(
	private val counterRepository: SaleInventoryCounterRepository,
	private val saleRepository: SaleRepository,
	private val expiredReservationReleaseService: ExpiredInventoryReservationReleaseService,
	private val clock: Clock,
) {
	@Transactional(isolation = Isolation.REPEATABLE_READ, timeout = SNAPSHOT_TIMEOUT_SECONDS)
	fun read(saleId: Long): InventorySnapshotResult {
		val now = clock.instant()
		expiredReservationReleaseService.releaseExpired(saleId, now)
		val saleQuantity = saleRepository.findQuantityById(saleId)
			?: return InventorySnapshotResult.InitializationFailed
		val committedQuantity = counterRepository.findCommittedQuantity(saleId)
			?: return InventorySnapshotResult.InitializationFailed
		val availableQuantity = saleQuantity.toLong() - committedQuantity.toLong()
		if (availableQuantity !in 0L..Int.MAX_VALUE.toLong()) {
			return InventorySnapshotResult.InitializationFailed
		}
		return InventorySnapshotResult.Available(availableQuantity.toInt())
	}

	private companion object {
		const val SNAPSHOT_TIMEOUT_SECONDS = 2
	}
}
