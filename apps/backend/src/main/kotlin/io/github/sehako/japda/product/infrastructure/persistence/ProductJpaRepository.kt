package io.github.sehako.japda.product.infrastructure.persistence

import io.github.sehako.japda.product.domain.model.Product
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProductJpaRepository : JpaRepository<Product, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select product from Product product where product.id = :id")
	fun findByIdForUpdate(@Param("id") id: Long): Product?

	@Query(value = """
		SELECT id, name FROM products
		WHERE seller_id = :sellerId AND status = 'READY'
		ORDER BY id DESC LIMIT :limit
	""", nativeQuery = true)
	fun findReadyLatest(sellerId: Long, limit: Int): List<ReadyProductProjection>

	@Query(value = """
		SELECT id, name FROM products
		WHERE seller_id = :sellerId AND status = 'READY' AND id < :cursorId
		ORDER BY id DESC LIMIT :limit
	""", nativeQuery = true)
	fun findReadyLatestAfter(sellerId: Long, cursorId: Long, limit: Int): List<ReadyProductProjection>

	@Query(value = """
		SELECT id, name FROM products
		WHERE seller_id = :sellerId AND status = 'READY'
		ORDER BY id ASC LIMIT :limit
	""", nativeQuery = true)
	fun findReadyOldest(sellerId: Long, limit: Int): List<ReadyProductProjection>

	@Query(value = """
		SELECT id, name FROM products
		WHERE seller_id = :sellerId AND status = 'READY' AND id > :cursorId
		ORDER BY id ASC LIMIT :limit
	""", nativeQuery = true)
	fun findReadyOldestAfter(sellerId: Long, cursorId: Long, limit: Int): List<ReadyProductProjection>

	@Query(value = """
		SELECT id, name FROM products
		WHERE seller_id = :sellerId AND status = 'READY'
		ORDER BY name COLLATE "C" ASC, id ASC LIMIT :limit
	""", nativeQuery = true)
	fun findReadyNameAscending(sellerId: Long, limit: Int): List<ReadyProductProjection>

	@Query(value = """
		SELECT id, name FROM products
		WHERE seller_id = :sellerId AND status = 'READY'
		  AND (name COLLATE "C", id) > (CAST(:cursorName AS text) COLLATE "C", :cursorId)
		ORDER BY name COLLATE "C" ASC, id ASC LIMIT :limit
	""", nativeQuery = true)
	fun findReadyNameAscendingAfter(sellerId: Long, cursorName: String, cursorId: Long, limit: Int): List<ReadyProductProjection>

	@Query(value = """
		SELECT id, name FROM products
		WHERE seller_id = :sellerId AND status = 'READY'
		ORDER BY name COLLATE "C" DESC, id DESC LIMIT :limit
	""", nativeQuery = true)
	fun findReadyNameDescending(sellerId: Long, limit: Int): List<ReadyProductProjection>

	@Query(value = """
		SELECT id, name FROM products
		WHERE seller_id = :sellerId AND status = 'READY'
		  AND (name COLLATE "C", id) < (CAST(:cursorName AS text) COLLATE "C", :cursorId)
		ORDER BY name COLLATE "C" DESC, id DESC LIMIT :limit
	""", nativeQuery = true)
	fun findReadyNameDescendingAfter(sellerId: Long, cursorName: String, cursorId: Long, limit: Int): List<ReadyProductProjection>
}

interface ReadyProductProjection {
	val id: Long
	val name: String
}
