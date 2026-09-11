package io.github.sehako.japda.sale.application.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class SaleDailyCapacity(
	@Value("\${sale.daily-capacity:20}")
	val value: Int,
) {
	init {
		require(value > 0) { "판매일별 정원은 양수여야 합니다." }
	}
}
