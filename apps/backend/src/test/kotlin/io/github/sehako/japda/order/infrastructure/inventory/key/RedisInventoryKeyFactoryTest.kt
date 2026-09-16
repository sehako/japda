package io.github.sehako.japda.order.infrastructure.inventory.key

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Redis 재고 key 생성")
class RedisInventoryKeyFactoryTest {
    private val keyFactory = RedisInventoryKeyFactory("test")

    @Test
    fun `같은_판매_일정의_key는_같은_hash_tag를_사용한다`() {
        assertEquals("test:inventory:{42}:stock", keyFactory.stock(42L))
        assertEquals("test:inventory:{42}:initialize-lock", keyFactory.initializationLock(42L))
        assertEquals("test:inventory:{42}:reservation:reservation-1", keyFactory.reservation(42L, "reservation-1"))
    }
}
