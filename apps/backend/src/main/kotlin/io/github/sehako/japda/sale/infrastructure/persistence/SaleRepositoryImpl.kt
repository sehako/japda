package io.github.sehako.japda.sale.infrastructure.persistence

import io.github.sehako.japda.sale.domain.model.Sale
import io.github.sehako.japda.sale.domain.repository.SaleCommittedQuantityIncreaseResult
import io.github.sehako.japda.sale.domain.repository.SaleRepository
import io.github.sehako.japda.sale.exception.SaleSellerAlreadyRegisteredPersistenceException
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class SaleRepositoryImpl(
	private val saleJpaRepository: SaleJpaRepository,
	private val jdbcTemplate: JdbcTemplate,
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

	override fun increaseCommittedQuantity(saleId: Long, quantity: Int): SaleCommittedQuantityIncreaseResult {
		val updated = jdbcTemplate.update(
			"""UPDATE sales
				SET committed_quantity = committed_quantity + ?
				WHERE id = ?
				  AND ? > 0
				  AND committed_quantity <= quantity - ?""",
			quantity,
			saleId,
			quantity,
			quantity,
		)
		if (updated == 1) return SaleCommittedQuantityIncreaseResult.Increased

		val quantities = jdbcTemplate.query(
			"SELECT quantity, committed_quantity FROM sales WHERE id = ?",
			{ result, _ -> result.getLong("quantity") to result.getLong("committed_quantity") },
			saleId,
		).singleOrNull() ?: return SaleCommittedQuantityIncreaseResult.MissingSale
		val (saleQuantity, committedQuantity) = quantities
		if (committedQuantity < 0 || committedQuantity > saleQuantity) {
			throw IllegalStateException("판매 일정의 점유 수량이 유효 범위를 벗어났습니다.")
		}
		return SaleCommittedQuantityIncreaseResult.Insufficient((saleQuantity - committedQuantity).toInt())
	}

	override fun decreaseCommittedQuantity(saleId: Long, quantity: Int): Boolean =
		jdbcTemplate.update(
			"""UPDATE sales
				SET committed_quantity = committed_quantity - ?
				WHERE id = ?
				  AND ? > 0
				  AND committed_quantity >= ?""",
			quantity,
			saleId,
			quantity,
			quantity,
		) == 1

	override fun existsBySellerIdAndSaleDate(sellerId: Long, saleDate: LocalDate): Boolean =
		saleJpaRepository.existsBySellerIdAndSaleDate(sellerId, saleDate)

	override fun findQuantityById(id: Long): Int? = saleJpaRepository.findQuantityById(id)

	override fun findById(id: Long): Sale? = saleJpaRepository.findById(id).orElse(null)

	override fun findByIdForUpdate(id: Long): Sale? = saleJpaRepository.findByIdForUpdate(id)

	private companion object {
		const val SELLER_SALE_DATE_UNIQUE_CONSTRAINT = "sales_seller_sale_date_unique"
	}
}
