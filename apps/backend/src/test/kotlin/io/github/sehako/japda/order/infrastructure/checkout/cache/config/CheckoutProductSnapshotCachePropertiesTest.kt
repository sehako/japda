package io.github.sehako.japda.order.infrastructure.checkout.cache.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@DisplayName("체크아웃 상품 스냅샷 캐시 설정")
class CheckoutProductSnapshotCachePropertiesTest {
	@Test
	fun `기본값은_Redis_캐시를_비활성화하고_SWR_만료_정책을_사용한다`() {
		val properties = RedisCheckoutProductSnapshotCacheProperties()

		assertFalse(properties.enabled)
		assertEquals("japda", properties.namespace)
		assertEquals(Duration.ofMillis(500), properties.connectTimeout)
		assertEquals(Duration.ofMillis(200), properties.commandTimeout)
		assertEquals(Duration.ofMinutes(5), properties.freshTtl)
		assertEquals(Duration.ofMinutes(55), properties.staleTtl)
		assertEquals(Duration.ofMinutes(65), properties.physicalTtl)
	}
}
