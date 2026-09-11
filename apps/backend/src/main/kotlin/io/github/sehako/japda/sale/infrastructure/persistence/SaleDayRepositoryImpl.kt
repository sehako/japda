package io.github.sehako.japda.sale.infrastructure.persistence

import io.github.sehako.japda.sale.domain.model.SaleDay
import io.github.sehako.japda.sale.domain.repository.SaleDayRepository
import java.time.LocalDate
import org.springframework.stereotype.Repository

@Repository
class SaleDayRepositoryImpl(
	private val saleDayJpaRepository: SaleDayJpaRepository,
) : SaleDayRepository {
	override fun createIfAbsent(saleDate: LocalDate, capacity: Int) {
		saleDayJpaRepository.createIfAbsent(saleDate, capacity)
	}

	override fun findBySaleDateForUpdate(saleDate: LocalDate): SaleDay? =
		saleDayJpaRepository.findBySaleDateForUpdate(saleDate)

	override fun save(saleDay: SaleDay): SaleDay = saleDayJpaRepository.save(saleDay)
}
