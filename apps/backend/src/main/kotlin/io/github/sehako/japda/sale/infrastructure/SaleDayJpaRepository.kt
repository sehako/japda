package io.github.sehako.japda.sale.infrastructure

import io.github.sehako.japda.sale.domain.SaleDay
import jakarta.persistence.LockModeType
import java.time.LocalDate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SaleDayJpaRepository : JpaRepository<SaleDay, LocalDate> {
	@Modifying
	@Query(
		value = """INSERT INTO sale_days (sale_date, capacity, registered_count)
			VALUES (:saleDate, :capacity, 0)
			ON CONFLICT (sale_date) DO NOTHING""",
		nativeQuery = true,
	)
	fun createIfAbsent(
		@Param("saleDate") saleDate: LocalDate,
		@Param("capacity") capacity: Int,
	)

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select saleDay from SaleDay saleDay where saleDay.saleDate = :saleDate")
	fun findBySaleDateForUpdate(@Param("saleDate") saleDate: LocalDate): SaleDay?
}
