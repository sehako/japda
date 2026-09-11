package io.github.sehako.japda.sale.infrastructure

import io.github.sehako.japda.sale.domain.Sale
import jakarta.persistence.LockModeType
import java.time.LocalDate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SaleJpaRepository : JpaRepository<Sale, Long> {
	fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate): Boolean

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select sale from Sale sale where sale.id = :id")
	fun findByIdForUpdate(@Param("id") id: Long): Sale?
}
