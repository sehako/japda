package io.github.sehako.japda.product.infrastructure.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("판매 준비 완료 상품 목록 인덱스 migration")
class ReadyProductIndexMigrationTest {
	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	@DisplayName("식별자 정렬 인덱스는 필터와 경계 열 뒤에 상품명을 포함한다")
	fun 식별자_정렬_인덱스_필터와_경계_열_뒤에_상품명을_포함한다() {
		assertEquals(
			"CREATE INDEX products_seller_ready_id_idx ON public.products USING btree (seller_id, status, id) INCLUDE (name)",
			indexDefinition("products_seller_ready_id_idx"),
		)
	}

	@Test
	@DisplayName("상품명 정렬 인덱스는 C collation 상품명과 식별자를 키로 사용한다")
	fun 상품명_정렬_인덱스_C_collation_상품명과_식별자를_키로_사용한다() {
		assertEquals(
			"CREATE INDEX products_seller_ready_name_id_idx ON public.products USING btree (seller_id, status, name COLLATE \"C\", id)",
			indexDefinition("products_seller_ready_name_id_idx"),
		)
	}

	private fun indexDefinition(indexName: String): String = jdbcTemplate.queryForObject(
		"SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
		String::class.java,
		indexName,
	)!!

	companion object {
		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
