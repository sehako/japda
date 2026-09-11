package io.github.sehako.japda.product.application.cursor

import io.github.sehako.japda.product.domain.repository.ReadyProductCursorBoundary
import io.github.sehako.japda.product.domain.repository.ReadyProductSort
import io.github.sehako.japda.product.domain.repository.ReadyProductSummary
import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.DisplayName
import tools.jackson.databind.json.JsonMapper

@DisplayName("상품 목록 커서 코덱")
class ReadyProductCursorCodecTest {
	private val codec = ReadyProductCursorCodec(JsonMapper.builder().build())

	@Test
	@DisplayName("식별자 정렬 커서는 패딩 없는 Base64 URL-safe 형식으로 인코딩하고 복원한다")
	fun 식별자_정렬_커서_인코딩하고_복원한다() {
		val encoded = codec.encode(ReadyProductSort.LATEST, ReadyProductSummary(37L, "한정판 상품"))

		assertFalse(encoded.contains('='))
		assertEquals(ReadyProductCursorBoundary.Id(37L), codec.decode(encoded, ReadyProductSort.LATEST))
	}

	@Test
	@DisplayName("상품명 정렬 커서는 상품명과 식별자를 복원한다")
	fun 상품명_정렬_커서_상품명과_식별자를_복원한다() {
		val encoded = codec.encode(ReadyProductSort.NAME_ASC, ReadyProductSummary(37L, "콜\"라보 상품"))

		assertEquals(ReadyProductCursorBoundary.Name("콜\"라보 상품", 37L), codec.decode(encoded, ReadyProductSort.NAME_ASC))
	}

	@Test
	@DisplayName("손상되거나 요청 정렬과 다른 커서를 거부한다")
	fun 잘못된_커서_거부한다() {
		listOf(
			"%%%",
			payload("""{"version":2,"sort":"latest","id":1}"""),
			payload("""{"version":1,"sort":"latest","id":0}"""),
			payload("""{"version":1,"sort":"name-asc","id":1}"""),
			payload("""{"version":1,"sort":"latest","id":1.2}"""),
			codec.encode(ReadyProductSort.OLDEST, ReadyProductSummary(1L, "상품")),
		).forEach { cursor ->
			val exception = assertFailsWith<ProductException> { codec.decode(cursor, ReadyProductSort.LATEST) }
			assertEquals(ProductErrorCode.CURSOR_INVALID, exception.errorCode)
		}
	}

	private fun payload(json: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
}
