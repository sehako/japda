package io.github.sehako.japda.sale.presentation

import io.github.sehako.japda.global.exception.CommonErrorCode
import io.github.sehako.japda.global.exception.CommonException
import io.github.sehako.japda.sale.application.SaleResponse
import io.github.sehako.japda.sale.application.SaleService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sales")
class SaleController(
	private val saleService: SaleService,
) {
	@PostMapping
	fun create(
		@RequestHeader(name = SELLER_ID_HEADER, required = false) sellerIdHeader: String?,
		@RequestBody request: CreateSaleRequest,
	): ResponseEntity<SaleResponse> {
		val sellerId = parseSellerId(sellerIdHeader)
		return ResponseEntity.status(HttpStatus.CREATED).body(saleService.create(request.toDto(sellerId)))
	}

	private fun parseSellerId(value: String?): Long {
		if (value == null) throw CommonException(CommonErrorCode.REQUEST_HEADER_MISSING)
		return value.toLongOrNull() ?: throw CommonException(CommonErrorCode.REQUEST_HEADER_INVALID)
	}

	private companion object {
		const val SELLER_ID_HEADER = "X-Seller-Id"
	}
}
