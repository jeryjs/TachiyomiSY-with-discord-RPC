package mihon.app.di

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfigurableDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConnectionFactory
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.util.storage.CbzCrypto
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.core.firebase.FirebaseConfig
import mihon.core.metro.AppCoroutineScope
import mihon.core.metro.IsDebugBuild
import mihon.data.database.DatabaseRepositoryImpl
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

@BindingContainer
object AppBindings {

    /**
     * WAL on every device, so a write never blocks reads. Low-RAM devices get one reader instead of four, since each
     * connection keeps its own page cache.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun providesSqlDriver(
        context: Context,
        // SY -->
        securityPreferences: SecurityPreferences,
        cbzCrypto: CbzCrypto,
        // SY <--
        databaseRepository: DatabaseRepositoryImpl,
    ): SqlDriver {
        val isLowRam = context.getSystemService<ActivityManager>()?.isLowRamDevice == true
        val configuration = AndroidxSqliteConfiguration(
            isForeignKeyConstraintsEnabled = true,
            concurrencyModel = MultipleReadersSingleWriter(isWal = true, walCount = if (isLowRam) 1 else 4),
        )
        // Runs before the driver compares the schema version, and onOpen once the schema is ready
        val onConfigure: suspend AndroidxSqliteConfigurableDriver.() -> Unit = {
            val version = executePragmaQuery("user_version", { QueryResult.Value(it.apply { next() }.getLong(0)) })
            databaseRepository.isMigrating.value = version in 1..<Database.Schema.version
        }
        val onOpen: suspend SqlDriver.() -> Unit = {
            databaseRepository.isMigrating.value = false
        }
        // SY -->
        if (securityPreferences.encryptDatabase.get()) {
            System.loadLibrary("sqlcipher")

            return AndroidxSqliteDriver(
                connectionFactory = busyTimeoutConnectionFactory(
                    SQLCipherDriver(
                        cbzCrypto.getDecryptedPasswordSql(),
                        null,
                        null,
                    ),
                ),
                databaseType = AndroidxSqliteDatabaseType.FileProvider(context, CbzCrypto.DATABASE_NAME),
                schema = Database.Schema,
                configuration = configuration,
                onConfigure = onConfigure,
                onOpen = onOpen,
            )
        }
        // SY <--
        return AndroidxSqliteDriver(
            connectionFactory = busyTimeoutConnectionFactory(BundledSQLiteDriver()),
            databaseType = AndroidxSqliteDatabaseType.FileProvider(context, "tachiyomi.db"),
            schema = Database.Schema,
            configuration = configuration,
            onConfigure = onConfigure,
            onOpen = onOpen,
        )
    }

    private fun busyTimeoutConnectionFactory(sqliteDriver: SQLiteDriver) = object : AndroidxSqliteConnectionFactory {
        override val driver: SQLiteDriver = sqliteDriver

        override fun createConnection(name: String): SQLiteConnection {
            return driver.open(name).apply {
                execSQL("PRAGMA busy_timeout = 3000")
            }
        }
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesDatabase(driver: SqlDriver): Database {
        return Database(
            driver = driver,
            historyAdapter = History.Adapter(
                last_readAdapter = DateColumnAdapter,
            ),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(
                memoAdapter = MemoColumnAdapter,
            ),
        )
    }

    @Provides
    @SingleIn(AppScope::class)
    @AppCoroutineScope
    fun providesAppCoroutineScope(@IsDebugBuild isDebugBuild: Boolean): CoroutineScope {
        // Work here outlives the screen that started it, so a failure would crash the app on whatever screen is open
        // by then. Debug builds still crash so the bug gets noticed.
        val handler = CoroutineExceptionHandler { _, throwable ->
            if (isDebugBuild) {
                val thread = Thread.currentThread()
                thread.uncaughtExceptionHandler?.uncaughtException(thread, throwable)
            } else {
                logcat(LogPriority.ERROR, throwable) { "Uncaught exception in the app scope" }
                FirebaseConfig.recordException(throwable)
            }
        }
        return CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesXML(): XML = XML.v1 {
        policy {
            ignoreUnknownChildren()
            autoPolymorphic = true
        }
        xmlDeclMode = XmlDeclMode.Charset
        xmlVersion = XmlVersion.XML10
        setIndent(2)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesProtoBuf(): ProtoBuf = ProtoBuf
}
