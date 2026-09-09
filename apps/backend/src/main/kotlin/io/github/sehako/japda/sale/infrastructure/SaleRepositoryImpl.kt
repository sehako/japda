package io.github.sehako.japda.sale.infrastructure

import io.github.sehako.japda.sale.domain.Sale
import io.github.sehako.japda.sale.domain.SalePeriodConflictException
import io.github.sehako.japda.sale.domain.SaleRepository
import java.sql.SQLException
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
		if (exception.hasSalePeriodConstraintViolation()) {
			throw SalePeriodConflictException(exception)
		}
		throw exception
	}

	private fun Throwable.hasSalePeriodConstraintViolation(): Boolean =
		generateSequence(this) { it.cause }.any { cause ->
			when (cause) {
				is ConstraintViolationException -> cause.constraintName == SALE_PERIOD_CONSTRAINT
				is SQLException -> cause.sqlState == EXCLUSION_VIOLATION_SQL_STATE &&
					cause.message?.contains(quotedSalePeriodConstraint) == true
				else -> false
			}
		}

	private companion object {
		const val SALE_PERIOD_CONSTRAINT = "ex_sales_product_period"
		const val EXCLUSION_VIOLATION_SQL_STATE = "23P01"
		val quotedSalePeriodConstraint = "\"$SALE_PERIOD_CONSTRAINT\""
	}
}
