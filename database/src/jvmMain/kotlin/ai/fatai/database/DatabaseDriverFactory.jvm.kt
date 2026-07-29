package ai.fatai.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ai.fatai.database.WatsonDatabase
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        val userHome = System.getProperty("user.home")
        val dbFolder = File(userHome, ".fatai")
        if (!dbFolder.exists()) dbFolder.mkdirs()
        val path = File(dbFolder, "app.db").absolutePath
        val databaseFile = File(path)
        val legacyDatabase = File(File(userHome, ".ai-assistant"), "app.db")

        if (!databaseFile.exists() && legacyDatabase.exists()) {
            legacyDatabase.copyTo(databaseFile)
        }

        val shouldCreateSchema = !databaseFile.exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")

        if (shouldCreateSchema) {
            WatsonDatabase.Schema.create(driver)
            writeSchemaVersion(path, WatsonDatabase.Schema.version)
        } else {
            val storedVersion = readSchemaVersion(path)
            val currentVersion = if (storedVersion == 0L) {
                inferSchemaVersion(path)
                    ?: error("Unable to determine the schema version for existing database: $path")
            } else {
                storedVersion
            }

            require(currentVersion <= WatsonDatabase.Schema.version) {
                "Database schema version $currentVersion is newer than supported version ${WatsonDatabase.Schema.version}"
            }

            if (currentVersion < WatsonDatabase.Schema.version) {
                WatsonDatabase.Schema.migrate(driver, currentVersion, WatsonDatabase.Schema.version)
            }
            writeSchemaVersion(path, WatsonDatabase.Schema.version)
        }

        return driver
    }

    private fun readSchemaVersion(path: String): Long = withConnection(path) { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { resultSet ->
                resultSet.next()
                resultSet.getLong(1)
            }
        }
    }

    /** Recovers databases created by older desktop builds that did not persist user_version. */
    private fun inferSchemaVersion(path: String): Long? = withConnection(path) { connection ->
        when {
            hasTable(connection, "UserAccount") -> WatsonDatabase.Schema.version
            hasColumn(connection, "FileAsset", "messageId") -> 6L
            hasColumn(connection, "ChatItem", "contentType") -> 5L
            hasTable(connection, "AppSetting") -> 4L
            hasTable(connection, "Workspace") -> 3L
            hasTable(connection, "Conversation") -> 2L
            else -> null
        }
    }

    private fun writeSchemaVersion(path: String, version: Long) {
        withConnection(path) { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA user_version = $version")
            }
        }
    }

    private fun hasTable(connection: Connection, tableName: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?"
        ).use { statement ->
            statement.setString(1, tableName)
            statement.executeQuery().use { it.next() }
        }

    private fun hasColumn(connection: Connection, tableName: String, columnName: String): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($tableName)").use { resultSet ->
                generateSequence { if (resultSet.next()) resultSet else null }
                    .any { it.getString("name") == columnName }
            }
        }

    private fun <T> withConnection(path: String, block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:$path").use(block)
}
