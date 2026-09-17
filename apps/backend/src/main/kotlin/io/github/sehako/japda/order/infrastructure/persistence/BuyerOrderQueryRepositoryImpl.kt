package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.model.OrderStatus
import io.github.sehako.japda.order.domain.repository.BuyerOrderQuery
import io.github.sehako.japda.order.domain.repository.BuyerOrderQueryRepository
import io.github.sehako.japda.order.domain.repository.BuyerOrderSummary
import java.sql.Timestamp
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class BuyerOrderQueryRepositoryImpl(
	private val jdbcTemplate: NamedParameterJdbcTemplate,
) : BuyerOrderQueryRepository {
	override fun findAll(query: BuyerOrderQuery): List<BuyerOrderSummary> {
		val parameters = MapSqlParameterSource()
			.addValue("buyerId", query.buyerId)
			.addValue("limit", query.limit)
		val sql = if (query.cursor == null) {
			FIND_FIRST_PAGE
		} else {
			parameters
				.addValue("cursorCreatedAt", Timestamp.from(query.cursor.createdAt))
				.addValue("cursorOrderId", query.cursor.orderId)
			FIND_AFTER_CURSOR
		}
		return jdbcTemplate.query(sql, parameters) { resultSet, _ ->
			BuyerOrderSummary(
				orderId = resultSet.getLong("id"),
				status = OrderStatus.valueOf(resultSet.getString("status")),
				productName = resultSet.getString("product_name"),
				quantity = resultSet.getInt("quantity"),
				unitPrice = resultSet.getLong("unit_price"),
				totalPrice = resultSet.getLong("total_price"),
				createdAt = resultSet.getTimestamp("created_at").toInstant(),
				expiresAt = resultSet.getTimestamp("expires_at").toInstant(),
			)
		}
	}

	private companion object {
		const val SELECT_COLUMNS = """SELECT id, status, product_name, quantity, unit_price, total_price, created_at, expires_at
			FROM orders"""
		const val FIND_FIRST_PAGE = """$SELECT_COLUMNS
			WHERE buyer_id = :buyerId
			ORDER BY created_at DESC, id DESC
			LIMIT :limit"""
		const val FIND_AFTER_CURSOR = """$SELECT_COLUMNS
			WHERE buyer_id = :buyerId
			  AND (created_at, id) < (:cursorCreatedAt, :cursorOrderId)
			ORDER BY created_at DESC, id DESC
			LIMIT :limit"""
	}
}
