package io.github.sehako.japda.order.application.inventory.snapshot

import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class InventorySnapshotService(
	private val orderRepository: OrderRepository,
	private val saleRepository: SaleRepository,
	private val clock: Clock,
) {
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = SNAPSHOT_TIMEOUT_SECONDS)
	fun read(saleId: Long): InventorySnapshotResult {
		val saleQuantity = saleRepository.findQuantityById(saleId)
			?: return InventorySnapshotResult.InitializationFailed
		val committedQuantity = orderRepository.sumCommittedQuantity(saleId, clock.instant())
		val availableQuantity = saleQuantity.toLong() - committedQuantity
		if (availableQuantity !in 0L..Int.MAX_VALUE.toLong()) {
			return InventorySnapshotResult.InitializationFailed
		}
		return InventorySnapshotResult.Available(availableQuantity.toInt())
	}

	private companion object {
		const val SNAPSHOT_TIMEOUT_SECONDS = 2
	}
}
