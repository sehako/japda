package io.github.sehako.japda.product.infrastructure

import io.github.sehako.japda.product.domain.Product
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProductJpaRepository : JpaRepository<Product, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select product from Product product where product.id = :id")
	fun findByIdForUpdate(@Param("id") id: Long): Product?
}
