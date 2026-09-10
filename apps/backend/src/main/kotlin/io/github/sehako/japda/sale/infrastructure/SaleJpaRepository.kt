package io.github.sehako.japda.sale.infrastructure

import io.github.sehako.japda.sale.domain.Sale
import java.time.LocalDate
import org.springframework.data.jpa.repository.JpaRepository

interface SaleJpaRepository : JpaRepository<Sale, Long> {
	fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate): Boolean
}
