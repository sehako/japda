package io.github.sehako.japda.auth.presentation.controller

import io.github.sehako.japda.auth.application.response.CurrentUserResponse
import io.github.sehako.japda.auth.application.service.CurrentUserService
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.exception.BusinessException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(private val currentUserService: CurrentUserService) {
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal userId: Long?): ResponseEntity<CurrentUserResponse> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(currentUserService.findCurrentUser(userId ?: throw BusinessException(AuthErrorCode.UNAUTHENTICATED)))
}
