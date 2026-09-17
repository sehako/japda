package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.repository.SaleInventoryCounterRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryReserveResult
import java.sql.Timestamp
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class SaleInventoryCounterRepositoryImpl(
	private val jdbcTemplate: JdbcTemplate,
) : SaleInventoryCounterRepository {
	override fun create(saleId: Long, now: Instant) {
		jdbcTemplate.update(
			"""INSERT INTO sale_inventory_counters (sale_id, committed_quantity, created_at, updated_at)
				VALUES (?, 0, ?, ?)""",
			saleId,
			Timestamp.from(now),
			Timestamp.from(now),
		)
	}

	override fun reserve(saleId: Long, quantity: Int, now: Instant): SaleInventoryReserveResult {
		val updated = jdbcTemplate.update(
			"""UPDATE sale_inventory_counters inventory
				SET committed_quantity = inventory.committed_quantity + ?,
					updated_at = ?
				FROM sales sale
				WHERE inventory.sale_id = sale.id
					AND inventory.sale_id = ?
					AND ? > 0
					AND inventory.committed_quantity <= sale.quantity - ?""",
			quantity,
			Timestamp.from(now),
			saleId,
			quantity,
			quantity,
		)
		if (updated == 1) return SaleInventoryReserveResult.ACQUIRED
		return if (findCommittedQuantity(saleId) == null) {
			SaleInventoryReserveResult.MISSING_COUNTER
		} else {
			SaleInventoryReserveResult.INSUFFICIENT
		}
	}

	override fun release(saleId: Long, quantity: Int, now: Instant): Boolean =
		jdbcTemplate.update(
			"""UPDATE sale_inventory_counters
				SET committed_quantity = committed_quantity - ?,
					updated_at = ?
				WHERE sale_id = ?
					AND ? > 0
					AND committed_quantity >= ?""",
			quantity,
			Timestamp.from(now),
			saleId,
			quantity,
			quantity,
		) == 1

	override fun findCommittedQuantity(saleId: Long): Int? = jdbcTemplate.query(
		"SELECT committed_quantity FROM sale_inventory_counters WHERE sale_id = ?",
		{ result, _ -> result.getInt("committed_quantity") },
		saleId,
	).singleOrNull()
}
