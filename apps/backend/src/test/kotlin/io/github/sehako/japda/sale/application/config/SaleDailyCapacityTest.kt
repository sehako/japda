package io.github.sehako.japda.sale.application.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.springframework.boot.test.context.runner.ApplicationContextRunner

@DisplayName("판매일별 정원 설정")
class SaleDailyCapacityTest {
	private val contextRunner = ApplicationContextRunner()
		.withUserConfiguration(SaleDailyCapacity::class.java)

	@Test
	@DisplayName("설정이 없으면 기본 정원 20을 사용한다")
	fun 설정_없음_기본_정원_20을_사용한다() {
		contextRunner.run { context ->
			assertEquals(20, context.getBean(SaleDailyCapacity::class.java).value)
		}
	}

	@Test
	@DisplayName("정원이 0 이하이면 애플리케이션 컨텍스트 시작을 실패한다")
	fun 정원_0_이하_애플리케이션_컨텍스트_시작을_실패한다() {
		contextRunner
			.withPropertyValues("sale.daily-capacity=0")
			.run { context ->
				assertNotNull(context.startupFailure)
			}
	}
}
