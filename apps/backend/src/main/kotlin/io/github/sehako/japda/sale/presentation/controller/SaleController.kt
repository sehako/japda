package io.github.sehako.japda.sale.presentation.controller

import io.github.sehako.japda.global.exception.CommonErrorCode
import io.github.sehako.japda.global.exception.CommonException
import io.github.sehako.japda.sale.application.response.SaleResponse
import io.github.sehako.japda.sale.application.service.SaleService
import io.github.sehako.japda.sale.presentation.request.CreateSaleRequest
import java.time.LocalDate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sales")
class SaleController(
	private val saleService: SaleService,
) {
	@GetMapping
	fun findBuyerSaleProducts(
		@RequestParam saleDate: LocalDate,
	) = saleService.findBuyerSaleProducts(saleDate)

	@GetMapping("/{saleId}")
	fun findBuyerSaleProductDetail(
		@PathVariable saleId: Long,
	) = saleService.findBuyerSaleProductDetail(saleId)

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
