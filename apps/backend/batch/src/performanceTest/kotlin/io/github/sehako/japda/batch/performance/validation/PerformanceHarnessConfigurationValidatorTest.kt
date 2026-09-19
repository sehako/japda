package io.github.sehako.japda.batch.performance.validation

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("성능 하네스 실행 설정 검산기")
class PerformanceHarnessConfigurationValidatorTest {
	@Test
	fun connection_pool이_worker수와_4개_여유보다_작으면_시작을_거부한다() {
		assertThatThrownBy {
			PerformanceHarnessConfigurationValidator.validate(workerCount = 8, maximumPoolSize = 11)
		}
			.isInstanceOf(IllegalStateException::class.java)
			.hasMessage("Hikari maximumPoolSize는 workerCount + 4 이상이어야 합니다: workerCount=8, required=12, actual=11")
	}
}
