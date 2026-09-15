package io.github.sehako.japda.payment.presentation.controller

import io.github.sehako.japda.auth.application.service.PrincipalIdentityService
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.exception.BusinessException
import io.github.sehako.japda.payment.application.response.PaymentResponse
import io.github.sehako.japda.payment.application.service.PaymentService
import io.github.sehako.japda.payment.presentation.request.ConfirmPaymentRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/payments")
class PaymentController(
	private val service: PaymentService,
	private val principalIdentityService: PrincipalIdentityService,
) {
	@PostMapping("/confirm")
	fun confirm(
		@AuthenticationPrincipal userId: Long?,
		@RequestBody request: ConfirmPaymentRequest,
	): PaymentResponse {
		val buyerId = principalIdentityService.buyerId(userId ?: throw BusinessException(AuthErrorCode.UNAUTHENTICATED))
		return service.confirm(request.toDto(buyerId))
	}
}
