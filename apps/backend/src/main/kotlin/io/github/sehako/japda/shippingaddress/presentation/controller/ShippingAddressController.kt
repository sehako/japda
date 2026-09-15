package io.github.sehako.japda.shippingaddress.presentation.controller

import io.github.sehako.japda.auth.application.service.PrincipalIdentityService
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.exception.BusinessException
import io.github.sehako.japda.shippingaddress.application.response.BuyerShippingAddressResponse
import io.github.sehako.japda.shippingaddress.application.service.BuyerShippingAddressService
import io.github.sehako.japda.shippingaddress.presentation.request.CreateBuyerShippingAddressRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/shipping-addresses")
class ShippingAddressController(
	private val service: BuyerShippingAddressService,
	private val principalIdentityService: PrincipalIdentityService,
) {
	@PostMapping
	fun create(
		@AuthenticationPrincipal userId: Long?,
		@RequestBody request: CreateBuyerShippingAddressRequest,
	): ResponseEntity<BuyerShippingAddressResponse> {
		val buyerId = principalIdentityService.buyerId(userId ?: throw BusinessException(AuthErrorCode.UNAUTHENTICATED))
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.toDto(buyerId)))
	}
}
