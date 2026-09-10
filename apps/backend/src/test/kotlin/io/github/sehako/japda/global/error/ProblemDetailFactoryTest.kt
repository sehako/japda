package io.github.sehako.japda.global.error

import io.github.sehako.japda.global.exception.ErrorCategory
import io.github.sehako.japda.global.exception.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName

@DisplayName("ProblemDetail 생성기")
class ProblemDetailFactoryTest {
	private val factory = ProblemDetailFactory()

	@Test
	@DisplayName("접근 거부 오류를 403으로 변환한다")
	fun 접근_거부_오류를_403으로_변환한다() {
		assertEquals(403, factory.create(error(ErrorCategory.FORBIDDEN), "/api/products/1/images").status)
	}

	@Test
	@DisplayName("용량 초과 오류를 413으로 변환한다")
	fun 용량_초과_오류를_413으로_변환한다() {
		assertEquals(413, factory.create(error(ErrorCategory.PAYLOAD_TOO_LARGE), "/api/products/1/images").status)
	}

	@Test
	@DisplayName("미지원 미디어 타입 오류를 415로 변환한다")
	fun 미지원_미디어_타입_오류를_415로_변환한다() {
		assertEquals(415, factory.create(error(ErrorCategory.UNSUPPORTED_MEDIA_TYPE), "/api/products/1/images").status)
	}

	private fun error(category: ErrorCategory) = object : ErrorCode {
		override val code = "TEST"
		override val message = "테스트"
		override val property: String? = null
		override val category = category
	}
}
