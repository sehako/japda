package io.github.sehako.japda.settlement.infrastructure.persistence

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
@DisplayName("정산 결제 조회 인덱스 migration")
class SettlementPaymentQueryIndexMigrationTest {
	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	@DisplayName("정산 결제 조회 인덱스는 상태와 승인 시각과 식별자 순서로 구성한다")
	fun 정산_결제_조회_인덱스는_상태와_승인_시각과_식별자_순서로_구성한다() {
		assertEquals(
			"CREATE INDEX payments_status_approved_at_id_idx ON public.payments USING btree (status, approved_at, id)",
			indexDefinition("payments_status_approved_at_id_idx"),
		)
	}

	private fun indexDefinition(indexName: String): String? = jdbcTemplate.query(
		"SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
		{ resultSet, _ -> resultSet.getString("indexdef") },
		indexName,
	).singleOrNull()

	companion object {
		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
