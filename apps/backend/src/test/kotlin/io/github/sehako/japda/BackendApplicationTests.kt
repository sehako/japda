package io.github.sehako.japda

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(PostgreSqlTestContainerConfiguration::class)
@DisplayName("백엔드 애플리케이션 컨텍스트")
class BackendApplicationTests(
	@Autowired private val jdbcTemplate: JdbcTemplate,
) {

	@Test
	@DisplayName("애플리케이션 시작_Flyway가 상품 테이블을 생성한다")
	fun 애플리케이션_시작_Flyway가_상품_테이블을_생성한다() {
		val tableCount = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'products'",
			Int::class.java,
		)

		assertEquals(1, tableCount)
	}

}
