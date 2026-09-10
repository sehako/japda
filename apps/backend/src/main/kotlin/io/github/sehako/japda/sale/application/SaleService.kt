package io.github.sehako.japda.sale.application

import io.github.sehako.japda.sale.exception.SaleErrorCode
import io.github.sehako.japda.sale.exception.SaleException
import io.github.sehako.japda.sale.exception.SaleSellerAlreadyRegisteredPersistenceException
import org.springframework.stereotype.Service

@Service
class SaleService(
	private val transactionService: SaleRegistrationTransactionService,
) {
	fun create(dto: CreateSaleDto): SaleResponse = try {
		transactionService.register(dto)
	} catch (_: SaleSellerAlreadyRegisteredPersistenceException) {
		throw SaleException(SaleErrorCode.SELLER_ALREADY_REGISTERED)
	}
}
