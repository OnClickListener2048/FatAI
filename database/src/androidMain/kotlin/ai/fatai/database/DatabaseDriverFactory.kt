package ai.fatai.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import ai.fatai.database.WatsonDatabase

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        val name = "app.db"
        // AndroidSqliteDriver invokes WatsonDatabase.Schema.migrate on upgrade.
        // Keeping the database preserves conversations while features evolve.
        return AndroidSqliteDriver(WatsonDatabase.Companion.Schema, context, name)
    }
}
