package io.github.sehako.japda.order.presentation.controller

import io.github.sehako.japda.auth.application.service.PrincipalIdentityService
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.exception.BusinessException
import io.github.sehako.japda.global.exception.CommonErrorCode
import io.github.sehako.japda.global.exception.CommonException
import io.github.sehako.japda.order.application.response.OrderResponse
import io.github.sehako.japda.order.application.response.BuyerOrderPageResponse
import io.github.sehako.japda.order.application.dto.ListBuyerOrdersDto
import io.github.sehako.japda.order.application.service.BuyerOrderHistoryService
import io.github.sehako.japda.order.application.service.OrderService
import io.github.sehako.japda.order.presentation.request.CreateOrderRequest
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/orders")
class OrderController(
	private val orderService: OrderService,
	private val buyerOrderHistoryService: BuyerOrderHistoryService,
	private val principalIdentityService: PrincipalIdentityService,
) {
	@GetMapping
	fun list(
		@AuthenticationPrincipal userId: Long?,
		@RequestParam(required = false) cursor: String?,
		@RequestParam(required = false) size: String?,
	): BuyerOrderPageResponse = buyerOrderHistoryService.list(
		ListBuyerOrdersDto(
			buyerId = principalIdentityService.buyerId(userId ?: throw BusinessException(AuthErrorCode.UNAUTHENTICATED)),
			cursor = cursor,
			size = parseSize(size),
		),
	)

	@PostMapping
	fun create(
		@AuthenticationPrincipal userId: Long?,
		@RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKeyHeader: String?,
		@RequestBody request: CreateOrderRequest,
	): ResponseEntity<OrderResponse> {
		val buyerId = principalIdentityService.buyerId(userId ?: throw BusinessException(AuthErrorCode.UNAUTHENTICATED))
		val idempotencyKey = parseIdempotencyKey(idempotencyKeyHeader)
		return ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(request.toDto(buyerId, idempotencyKey)))
	}

	private fun parseIdempotencyKey(value: String?): UUID {
		if (value == null) throw CommonException(CommonErrorCode.REQUEST_HEADER_MISSING)
		return try {
			UUID.fromString(value)
		} catch (_: IllegalArgumentException) {
			throw CommonException(CommonErrorCode.REQUEST_HEADER_INVALID)
		}
	}

	private fun parseSize(value: String?): Int = value?.toIntOrNull()
		?: if (value == null) DEFAULT_SIZE else throw CommonException(CommonErrorCode.REQUEST_PARAMETER_INVALID)

	private companion object {
		const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
		const val DEFAULT_SIZE = 20
	}
}
