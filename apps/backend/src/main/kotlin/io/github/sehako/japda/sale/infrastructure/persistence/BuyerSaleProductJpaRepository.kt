package io.github.sehako.japda.sale.infrastructure.persistence

import io.github.sehako.japda.sale.domain.model.Sale
import java.time.Instant
import java.time.LocalDate
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param

internal interface BuyerSaleProductJpaRepository : Repository<Sale, Long> {
	@Query(
		value = """
			SELECT s.id AS "saleId",
			       s.product_id AS "productId",
			       p.name AS "name",
			       p.description AS "description",
			       s.price AS "price",
			       s.quantity AS "quantity",
			       s.sale_date AS "saleDate",
			       s.created_at AS "createdAt",
			       pi.object_key AS "representativeImageObjectKey"
			FROM sales s
			INNER JOIN products p ON p.id = s.product_id
			INNER JOIN product_images pi
			        ON pi.product_id = p.id
			       AND pi.is_representative = true
			WHERE s.sale_date = :saleDate
			ORDER BY s.created_at ASC, s.id ASC
		""",
		nativeQuery = true,
	)
	fun findProjectionsBySaleDate(@Param("saleDate") saleDate: LocalDate): List<BuyerSaleProductProjection>

	@Query(
		value = """
			SELECT s.id AS "saleId",
			       s.product_id AS "productId",
			       p.name AS "name",
			       p.description AS "description",
			       s.price AS "price",
			       s.quantity AS "quantity",
			       s.sale_date AS "saleDate",
			       pi.object_key AS "imageObjectKey",
			       pi.display_order AS "imageDisplayOrder",
			       pi.is_representative AS "imageRepresentative"
			FROM sales s
			INNER JOIN products p ON p.id = s.product_id
			INNER JOIN product_images pi ON pi.product_id = p.id
			WHERE s.id = :saleId
			ORDER BY pi.display_order ASC
		""",
		nativeQuery = true,
	)
	fun findDetailProjectionsBySaleId(@Param("saleId") saleId: Long): List<BuyerSaleProductDetailProjection>
}

internal interface BuyerSaleProductProjection {
	val saleId: Long
	val productId: Long
	val name: String
	val description: String?
	val price: Long
	val quantity: Int
	val saleDate: LocalDate
	val createdAt: Instant
	val representativeImageObjectKey: String
}

internal interface BuyerSaleProductDetailProjection {
	val saleId: Long
	val productId: Long
	val name: String
	val description: String?
	val price: Long
	val quantity: Int
	val saleDate: LocalDate
	val imageObjectKey: String
	val imageDisplayOrder: Int
	val imageRepresentative: Boolean
}
