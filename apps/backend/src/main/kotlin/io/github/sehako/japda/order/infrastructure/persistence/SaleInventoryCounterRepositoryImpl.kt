package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.repository.SaleInventoryCounterRepository
import io.github.sehako.japda.order.domain.repository.SaleInventoryReserveResult
import java.sql.Timestamp
import java.time.Instant
import org.slf4j.LoggerFactory
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
		if (updated == 1) return SaleInventoryReserveResult.Acquired
		val inventory = findInventoryQuantities(saleId) ?: return SaleInventoryReserveResult.MissingCounter
		val remainingQuantity = inventory.saleQuantity - inventory.committedQuantity
		if (remainingQuantity < 0 || remainingQuantity > Int.MAX_VALUE) {
			logger.error(
				"판매 일정의 잔여 재고가 유효 범위를 벗어났습니다. saleId={}, saleQuantity={}, committedQuantity={}",
				saleId,
				inventory.saleQuantity,
				inventory.committedQuantity,
			)
			throw IllegalStateException("판매 일정의 잔여 재고가 유효 범위를 벗어났습니다.")
		}
		return SaleInventoryReserveResult.Insufficient(remainingQuantity.toInt())
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

	private fun findInventoryQuantities(saleId: Long): InventoryQuantities? = jdbcTemplate.query(
		"""SELECT sale.quantity AS sale_quantity, inventory.committed_quantity
			FROM sales sale
			LEFT JOIN sale_inventory_counters inventory ON inventory.sale_id = sale.id
			WHERE sale.id = ?""",
		{ result, _ ->
			val committedQuantity = (result.getObject("committed_quantity") as? Number)?.toLong()
			committedQuantity?.let {
				InventoryQuantities(
					saleQuantity = result.getLong("sale_quantity"),
					committedQuantity = it,
				)
			}
		},
		saleId,
	).singleOrNull()

	private data class InventoryQuantities(
		val saleQuantity: Long,
		val committedQuantity: Long,
	)

	private companion object {
		val logger = LoggerFactory.getLogger(SaleInventoryCounterRepositoryImpl::class.java)
	}
}
