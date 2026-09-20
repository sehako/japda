package io.github.sehako.japda.order.infrastructure.checkout.cache.key

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("체크아웃 상품 스냅샷 Redis key 생성")
class RedisCheckoutProductSnapshotKeyFactoryTest {
	private val keyFactory = RedisCheckoutProductSnapshotKeyFactory("checkout-test")

	@Test
	fun `상품_스냅샷_key는_namespace_버전과_판매_ID를_포함한다`() {
		assertEquals("checkout-test:checkout:product-snapshot:v1:42", keyFactory.snapshot(42L))
	}

}
