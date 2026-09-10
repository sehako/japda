package io.github.sehako.japda.sale.infrastructure

import io.github.sehako.japda.sale.domain.Sale
import io.github.sehako.japda.sale.domain.SaleRepository
import io.github.sehako.japda.sale.exception.SaleSellerAlreadyRegisteredPersistenceException
import java.time.LocalDate
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository

@Repository
class SaleRepositoryImpl(
	private val saleJpaRepository: SaleJpaRepository,
) : SaleRepository {
	override fun save(sale: Sale): Sale = try {
		saleJpaRepository.saveAndFlush(sale)
	} catch (exception: DataIntegrityViolationException) {
		val constraintViolation = generateSequence<Throwable>(exception) { it.cause }
			.filterIsInstance<ConstraintViolationException>()
			.firstOrNull()
		if (constraintViolation?.constraintName == SELLER_SALE_DATE_UNIQUE_CONSTRAINT) {
			throw SaleSellerAlreadyRegisteredPersistenceException(exception)
		}
		throw exception
	}

	override fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate): Boolean =
		saleJpaRepository.existsBySellerIdAndSaleDate(sellerId, saleDate)

	private companion object {
		const val SELLER_SALE_DATE_UNIQUE_CONSTRAINT = "sales_seller_sale_date_unique"
	}
}
