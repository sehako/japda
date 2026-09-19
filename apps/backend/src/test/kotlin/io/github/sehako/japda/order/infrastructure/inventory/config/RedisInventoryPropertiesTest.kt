package io.github.sehako.japda.order.infrastructure.inventory.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@DisplayName("Redis 재고 설정")
class RedisInventoryPropertiesTest {
    @Test
    fun `기본값은_기능을_비활성화하고_짧은_timeout과_고정_TTL을_사용한다`() {
        val properties = RedisInventoryProperties()

        assertFalse(properties.enabled)
        assertEquals("japda", properties.namespace)
        assertEquals(Duration.ofMillis(500), properties.connectTimeout)
        assertEquals(Duration.ofMillis(200), properties.commandTimeout)
        assertEquals(Duration.ofSeconds(30), properties.soldOutTtl)
    }
}
