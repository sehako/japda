package io.github.sehako.japda.product.domain.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName

@DisplayName("판매 준비 완료 상품 조회 조건")
class ReadyProductQueryTest {
    @Test
    @DisplayName("식별자 정렬에는 식별자 커서 경계를 사용한다")
    fun 식별자_정렬_식별자_커서_경계를_사용한다() {
        val boundary = ReadyProductCursorBoundary.Id(id = 41L)

        val query = ReadyProductQuery(
            sellerId = 1L,
            sort = ReadyProductSort.LATEST,
            cursor = boundary,
            limit = 21,
        )

        assertEquals(boundary, query.cursor)
    }

    @Test
    @DisplayName("상품명 정렬에는 상품명과 식별자 커서 경계를 사용한다")
    fun 상품명_정렬_상품명과_식별자_커서_경계를_사용한다() {
        val boundary = ReadyProductCursorBoundary.Name(name = "콜라보 상품", id = 37L)

        val query = ReadyProductQuery(
            sellerId = 1L,
            sort = ReadyProductSort.NAME_ASC,
            cursor = boundary,
            limit = 21,
        )

        assertEquals(boundary, query.cursor)
    }

    @Test
    @DisplayName("정렬과 커서 경계 종류가 다르면 조회 조건 생성을 거부한다")
    fun 정렬과_커서_경계가_다르면_조회_조건_생성을_거부한다() {
        assertFailsWith<IllegalArgumentException> {
            ReadyProductQuery(
                sellerId = 1L,
                sort = ReadyProductSort.NAME_DESC,
                cursor = ReadyProductCursorBoundary.Id(id = 37L),
                limit = 21,
            )
        }
    }
}
