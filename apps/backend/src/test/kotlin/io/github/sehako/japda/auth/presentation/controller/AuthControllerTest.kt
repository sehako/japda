package io.github.sehako.japda.auth.presentation.controller

import io.github.sehako.japda.auth.application.response.CurrentUserResponse
import io.github.sehako.japda.auth.application.service.CurrentUserService
import io.github.sehako.japda.global.error.GlobalExceptionHandler
import io.github.sehako.japda.global.error.ProblemDetailFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest
import org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse
import org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.MockMvcConfigurer
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder

@DisplayName("현재 사용자 API")
@ExtendWith(RestDocumentationExtension::class)
class AuthControllerTest {
    private lateinit var mockMvc: MockMvc
    private lateinit var service: CurrentUserService

    @BeforeEach
    fun setUp(restDocumentation: RestDocumentationContextProvider) {
        service = mock(CurrentUserService::class.java)
        val configurer: MockMvcConfigurer = documentationConfiguration(restDocumentation)
        mockMvc = MockMvcBuilders.standaloneSetup(AuthController(service))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(GlobalExceptionHandler(ProblemDetailFactory()))
            .apply<StandaloneMockMvcBuilder>(configurer)
            .build()
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    @DisplayName("인증된 사용자의 현재 정보를 JSON과 no-store 헤더로 반환하고 문서화한다")
    fun 인증된_사용자_현재_정보_반환과_문서화() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(123L, null, emptyList())
        `when`(service.findCurrentUser(123L)).thenReturn(
            CurrentUserResponse(123L, "buyer@example.com", listOf("ADMIN", "BUYER")),
        )

        mockMvc.perform(get("/api/auth/me").header("Cookie", "JAPDA_ACCESS_TOKEN=synthetic-token"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Content-Type", "application/json"))
            .andExpect(jsonPath("$.id").value(123))
            .andExpect(jsonPath("$.email").value("buyer@example.com"))
            .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
            .andExpect(jsonPath("$.roles[1]").value("BUYER"))
            .andDo(document(
                "auth-current-user",
                preprocessRequest(prettyPrint()),
                preprocessResponse(prettyPrint()),
                requestHeaders(headerWithName("Cookie").description("서비스 JWT가 저장된 JAPDA_ACCESS_TOKEN 쿠키")),
                responseHeaders(
                    headerWithName("Content-Type").description("application/json"),
                    headerWithName("Cache-Control").description("no-store"),
                ),
                responseFields(
                    fieldWithPath("id").description("내부 사용자 식별자"),
                    fieldWithPath("email").description("현재 저장된 이메일"),
                    fieldWithPath("roles").description("현재 저장된 역할을 중복 제거하여 이름순으로 정렬한 목록"),
                ),
            ))
    }

    @Test
    @DisplayName("인증된 사용자 ID가 없으면 401 ProblemDetail을 반환하고 문서화한다")
    fun 인증_정보_없음_401_문서화() {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Content-Type", "application/problem+json"))
            .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
            .andDo(document(
                "auth-current-user-unauthenticated",
                preprocessRequest(prettyPrint()),
                preprocessResponse(prettyPrint()),
                responseHeaders(
                    headerWithName("Content-Type").description("application/problem+json"),
                    headerWithName("Cache-Control").description("no-store"),
                ),
                responseFields(
                    fieldWithPath("type").description("문제 유형"),
                    fieldWithPath("title").description("인증 오류 제목"),
                    fieldWithPath("status").description("HTTP 상태 코드"),
                    fieldWithPath("detail").description("인증 오류 설명"),
                    fieldWithPath("instance").description("요청 경로"),
                    fieldWithPath("code").description("인증 오류 코드"),
                ),
            ))
    }
}
