package io.github.sehako.japda.batch.performance.database

import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("성능 테스트 PostgreSQL fixture")
class PerformancePostgresFixtureTest {
	@Test
	@DisplayName("iteration마다 독립 schema를 만들고 API root migration을 적용한다")
	fun iteration마다_독립_schema를_만들고_API_root_migration을_적용한다() {
		PerformancePostgresFixture(Path.of(System.getProperty("rootMigrationDirectory"))).use { fixture ->
			fixture.start()

			val first = fixture.createIteration()
			val second = fixture.createIteration()

			assertNotEquals(first.schema, second.schema)
			assertTrue(first.schema.matches(Regex("performance_iteration_[0-9]+_[a-f0-9]+")))
			assertTrue(tableExists(first, "settlement_runs"))
			assertTrue(tableExists(second, "batch_job_instance"))
			assertFalse(first.toString().contains("password=${first.password}"))
			assertTrue(fixture.runtime.containerId.isNotBlank())
		}
	}

	private fun tableExists(database: IterationDatabase, table: String): Boolean =
		DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
			connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
				statement.setString(1, "${database.schema}.$table")
				statement.executeQuery().use { result ->
					result.next()
					result.getBoolean(1)
				}
			}
		}
}
