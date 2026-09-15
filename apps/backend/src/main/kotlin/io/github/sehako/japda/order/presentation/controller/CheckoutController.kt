package io.github.sehako.japda.order.presentation.controller

import io.github.sehako.japda.auth.application.service.PrincipalIdentityService
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.exception.BusinessException
import io.github.sehako.japda.order.application.response.CheckoutResponse
import io.github.sehako.japda.order.application.service.CheckoutService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/checkout")
class CheckoutController(
	private val checkoutService: CheckoutService,
	private val principalIdentityService: PrincipalIdentityService,
) {
	@GetMapping
	fun find(
		@AuthenticationPrincipal userId: Long?,
		@RequestParam saleId: Long,
		@RequestParam quantity: Int,
	): CheckoutResponse {
		val buyerId = principalIdentityService.buyerId(userId ?: throw BusinessException(AuthErrorCode.UNAUTHENTICATED))
		return checkoutService.find(buyerId, saleId, quantity)
	}
}
