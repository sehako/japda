package io.github.sehako.japda.sale.presentation.controller

import io.github.sehako.japda.auth.application.service.PrincipalIdentityService
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.exception.BusinessException
import io.github.sehako.japda.sale.application.response.SaleResponse
import io.github.sehako.japda.sale.application.service.SaleService
import io.github.sehako.japda.sale.presentation.request.CreateSaleRequest
import java.time.LocalDate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sales")
class SaleController(
	private val saleService: SaleService,
	private val principalIdentityService: PrincipalIdentityService,
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
		@AuthenticationPrincipal userId: Long?,
		@RequestBody request: CreateSaleRequest,
	): ResponseEntity<SaleResponse> {
		val sellerId = principalIdentityService.sellerId(userId ?: throw BusinessException(AuthErrorCode.UNAUTHENTICATED))
		return ResponseEntity.status(HttpStatus.CREATED).body(saleService.create(request.toDto(sellerId)))
	}
}
