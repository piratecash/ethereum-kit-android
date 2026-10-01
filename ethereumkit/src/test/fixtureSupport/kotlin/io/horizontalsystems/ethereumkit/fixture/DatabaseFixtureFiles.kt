package io.horizontalsystems.ethereumkit.fixture

import android.content.Context
import androidx.room.RoomDatabase
import org.junit.Assume.assumeTrue
import java.io.File

/** Copies plaintext fixture databases between `src/test/resources/databases` and the Robolectric database directory. */
object DatabaseFixtureFiles {
    private const val RESOURCE_DIR = "databases"
    private const val REGENERATE_ENV = "REGENERATE_FIXTURES"

    fun install(context: Context, name: String) {
        val source = checkNotNull(javaClass.classLoader?.getResourceAsStream("$RESOURCE_DIR/$name")) {
            "Missing fixture $name"
        }
        val target = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        source.use { input -> target.outputStream().use(input::copyTo) }
    }

    /** Flushes the WAL into the main file, closes [database] and stores the single resulting file as a fixture. */
    fun export(context: Context, database: RoomDatabase, name: String) {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        database.close()
        val source = context.getDatabasePath(name)
        check(File(source.path + "-wal").length() == 0L) { "WAL of $name is not empty after checkpoint" }
        // Gradle runs unit tests with the module directory as the working directory.
        val target = File("src/test/resources/$RESOURCE_DIR/$name").also { it.parentFile?.mkdirs() }
        source.copyTo(target, overwrite = true)
    }

    /** Regenerate with: `REGENERATE_FIXTURES=1 ./gradlew :<module>:cleanTestDebugUnitTest :<module>:testDebugUnitTest --tests '*DatabaseFixtureGenerator'`. */
    fun assumeRegenerating() = assumeTrue(System.getenv(REGENERATE_ENV) == "1")
}
