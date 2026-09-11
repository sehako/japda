package io.github.sehako.japda.product.infrastructure.persistence

import io.github.sehako.japda.product.domain.repository.ProductRepository
import io.github.sehako.japda.product.domain.repository.ReadyProductCursorBoundary
import io.github.sehako.japda.product.domain.repository.ReadyProductQuery
import io.github.sehako.japda.product.domain.repository.ReadyProductSort
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProductRepositoryImpl::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("판매 준비 완료 상품 목록 영속성")
class ReadyProductRepositoryTest {
	@Autowired
	private lateinit var productRepository: ProductRepository

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@BeforeEach
	fun 상품을_준비한다() {
		insertProduct(1L, "같은 상품", "READY")
		insertProduct(1L, "한글", "READY")
		insertProduct(1L, " 같은 상품", "READY")
		insertProduct(1L, "Apple", "READY")
		insertProduct(1L, "apple", "READY")
		insertProduct(1L, "같은 상품", "READY")
		insertProduct(1L, "제외 초안", "DRAFT")
		insertProduct(2L, "제외 다른 판매자", "READY")
	}

	@Test
	@DisplayName("식별자 정렬은 판매자 READY 조건과 커서 방향을 적용한다")
	fun 식별자_정렬_판매자_READY_조건과_커서_방향을_적용한다() {
		val ids = readyIds()
		val cursorId = ids[3]

		assertEquals(ids.reversed().take(3), find(ReadyProductSort.LATEST, null, 3).map { it.id })
		assertEquals(ids.take(3).reversed().take(2), find(ReadyProductSort.LATEST, ReadyProductCursorBoundary.Id(cursorId), 2).map { it.id })
		assertEquals(ids.take(3), find(ReadyProductSort.OLDEST, null, 3).map { it.id })
		assertEquals(ids.drop(4).take(2), find(ReadyProductSort.OLDEST, ReadyProductCursorBoundary.Id(cursorId), 2).map { it.id })
	}

	@Test
	@DisplayName("상품명 정렬은 C collation 행 값 비교와 식별자 보조 정렬을 적용한다")
	fun 상품명_정렬_C_collation_행_값_비교와_식별자_보조_정렬을_적용한다() {
		val ids = readyIds()
		assertEquals(listOf(ids[2], ids[3], ids[4], ids[0], ids[5], ids[1]), find(ReadyProductSort.NAME_ASC, null, 10).map { it.id })
		assertEquals(
			listOf(ids[5], ids[1]),
			find(ReadyProductSort.NAME_ASC, ReadyProductCursorBoundary.Name("같은 상품", ids[0]), 10).map { it.id },
		)
		assertEquals(listOf(ids[1], ids[5], ids[0], ids[4], ids[3], ids[2]), find(ReadyProductSort.NAME_DESC, null, 10).map { it.id })
		assertEquals(
			listOf(ids[0], ids[4], ids[3], ids[2]),
			find(ReadyProductSort.NAME_DESC, ReadyProductCursorBoundary.Name("같은 상품", ids[5]), 10).map { it.id },
		)
	}

	private fun readyIds(): List<Long> = jdbcTemplate.queryForList(
		"SELECT id FROM products WHERE seller_id = 1 AND status = 'READY' ORDER BY id",
		Long::class.java,
	).map(::requireNotNull)

	private fun find(sort: ReadyProductSort, cursor: ReadyProductCursorBoundary?, limit: Int) =
		productRepository.findReadyProducts(ReadyProductQuery(1L, sort, cursor, limit))

	private fun insertProduct(sellerId: Long, name: String, status: String) {
		jdbcTemplate.update(
			"INSERT INTO products (seller_id, name, status, created_at) VALUES (?, ?, ?, now())",
			sellerId,
			name,
			status,
		)
	}

	companion object {
		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
