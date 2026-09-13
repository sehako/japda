package io.github.sehako.japda.order.presentation.controller

import io.github.sehako.japda.global.exception.CommonErrorCode
import io.github.sehako.japda.global.exception.CommonException
import io.github.sehako.japda.order.application.response.CheckoutResponse
import io.github.sehako.japda.order.application.service.CheckoutService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/checkout")
class CheckoutController(
	private val checkoutService: CheckoutService,
) {
	@GetMapping
	fun find(
		@RequestHeader(name = BUYER_ID_HEADER, required = false) buyerIdHeader: String?,
		@RequestParam saleId: Long,
		@RequestParam quantity: Int,
	): CheckoutResponse {
		val buyerId = parseBuyerId(buyerIdHeader)
		return checkoutService.find(buyerId, saleId, quantity)
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
