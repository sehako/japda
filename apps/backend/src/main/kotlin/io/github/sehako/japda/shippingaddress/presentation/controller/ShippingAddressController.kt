package io.github.sehako.japda.shippingaddress.presentation.controller

import io.github.sehako.japda.global.exception.CommonErrorCode
import io.github.sehako.japda.global.exception.CommonException
import io.github.sehako.japda.shippingaddress.application.response.BuyerShippingAddressResponse
import io.github.sehako.japda.shippingaddress.application.service.BuyerShippingAddressService
import io.github.sehako.japda.shippingaddress.presentation.request.CreateBuyerShippingAddressRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/shipping-addresses")
class ShippingAddressController(
	private val service: BuyerShippingAddressService,
) {
	@PostMapping
	fun create(
		@RequestHeader(name = BUYER_ID_HEADER, required = false) buyerIdHeader: String?,
		@RequestBody request: CreateBuyerShippingAddressRequest,
	): ResponseEntity<BuyerShippingAddressResponse> {
		val buyerId = parseBuyerId(buyerIdHeader)
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.toDto(buyerId)))
	}

	private fun parseBuyerId(value: String?): Long {
		if (value == null) throw CommonException(CommonErrorCode.REQUEST_HEADER_MISSING)
		return value.toLongOrNull()?.takeIf { it > 0 }
			?: throw CommonException(CommonErrorCode.REQUEST_HEADER_INVALID)
	}

	private companion object {
		const val BUYER_ID_HEADER = "X-Buyer-Id"
	}
}
