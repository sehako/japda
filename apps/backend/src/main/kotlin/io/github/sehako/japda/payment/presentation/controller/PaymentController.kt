package io.github.sehako.japda.payment.presentation.controller

import io.github.sehako.japda.global.exception.CommonErrorCode
import io.github.sehako.japda.global.exception.CommonException
import io.github.sehako.japda.payment.application.response.PaymentResponse
import io.github.sehako.japda.payment.application.service.PaymentService
import io.github.sehako.japda.payment.presentation.request.ConfirmPaymentRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/payments")
class PaymentController(private val service: PaymentService) {
	@PostMapping("/confirm")
	fun confirm(
		@RequestHeader(name = BUYER_ID_HEADER, required = false) buyerIdHeader: String?,
		@RequestBody request: ConfirmPaymentRequest,
	): PaymentResponse {
		if (buyerIdHeader == null) throw CommonException(CommonErrorCode.REQUEST_HEADER_MISSING)
		val buyerId = buyerIdHeader.toLongOrNull()?.takeIf { it > 0 }
			?: throw CommonException(CommonErrorCode.REQUEST_HEADER_INVALID)
		return service.confirm(request.toDto(buyerId))
	}

	private companion object {
		const val BUYER_ID_HEADER = "X-Buyer-Id"
	}
}
