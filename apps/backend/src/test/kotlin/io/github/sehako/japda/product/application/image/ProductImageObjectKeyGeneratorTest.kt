package io.github.sehako.japda.product.application.image

import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("상품 이미지 객체 키 생성기")
class ProductImageObjectKeyGeneratorTest {

	@Test
	@DisplayName("객체 키 생성_prefix와 상품 ID와 UUID와 검증된 확장자를 조합한다")
	fun 객체_키_생성_prefix와_상품_ID와_UUID와_검증된_확장자를_조합한다() {
		val generator = ProductImageObjectKeyGenerator(
			prefix = "/catalog-images/",
			uuidSupplier = { UUID.fromString("123e4567-e89b-12d3-a456-426614174000") },
		)

		val objectKey = generator.generate(productId = 41L, extension = "webp")

		assertEquals(
			"catalog-images/products/41/123e4567-e89b-12d3-a456-426614174000.webp",
			objectKey,
		)
	}
}
