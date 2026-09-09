package io.github.sehako.japda.sale.presentation

import io.github.sehako.japda.sale.application.SaleResponse
import io.github.sehako.japda.sale.application.SaleService
import java.net.URI
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/products/{productId}/sales")
class SaleController(
	private val saleService: SaleService,
	private val requestConverter: CreateSaleRequestConverter,
) {

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
	fun create(
		@PathVariable productId: Long,
		@RequestHeader(name = SELLER_ID_HEADER, required = false) sellerIdHeader: String?,
		@RequestBody request: CreateSaleRequest,
	): ResponseEntity<SaleResponse> {
		val dto = requestConverter.convert(sellerIdHeader, productId, request)
		val response = saleService.create(dto)

		return ResponseEntity.created(URI.create("/api/sales/${response.id}"))
			.body(response)
	}

	companion object {
		private const val SELLER_ID_HEADER = "X-Seller-Id"
	}
}
