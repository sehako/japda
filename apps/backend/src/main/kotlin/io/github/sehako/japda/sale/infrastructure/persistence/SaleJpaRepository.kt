package io.github.sehako.japda.sale.infrastructure.persistence

import io.github.sehako.japda.sale.domain.model.Sale
import jakarta.persistence.LockModeType
import java.time.LocalDate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SaleJpaRepository : JpaRepository<Sale, Long> {
	fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate): Boolean

	@Query("select sale.quantity from Sale sale where sale.id = :id")
	fun findQuantityById(@Param("id") id: Long): Int?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select sale from Sale sale where sale.id = :id")
	fun findByIdForUpdate(@Param("id") id: Long): Sale?
}
