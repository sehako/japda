package io.github.sehako.japda.order.infrastructure.inventory.key

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Redis 재고 key 생성")
class RedisInventoryKeyFactoryTest {
    private val keyFactory = RedisInventoryKeyFactory("test")

    @Test
    fun `품절_마커_key는_namespace와_판매_일정을_포함한다`() {
        assertEquals("test:inventory:42:sold-out", keyFactory.soldOut(42L))
    }
}
