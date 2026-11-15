package exh.log

import android.util.Log
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog
import com.elvishew.xlog.LogLevel as XLogLevel

/**
 * SY-specific file
 */
fun Any.xLog(): Logger = XLog.tag(this::class.java.simpleName).build()

fun Any.xLogStack(): Logger = XLog.tag(this::class.java.simpleName).enableStackTrace(0).build()

/**
 * Runs [block] only once XLog has been initialised.
 *
 * Fork logging can run before App.setupExhLogging() calls XLog.init(): the Metro graph is injected
 * eagerly, so AndroidSourceManager starts building the source map from its constructor, and
 * extension loading logs blacklisted extensions from there too. XLog throws IllegalStateException
 * in that window, and a log call must never be able to abort its caller - doing so killed the
 * source map build and left the library and sources screens loading forever.
 */
inline fun xLogSafely(block: () -> Unit) {
    try {
        block()
    } catch (_: IllegalStateException) {
        // XLog.init() has not run yet; drop the line instead of breaking the caller.
    }
}

/** Runs [block] and falls back to [default] when XLog has not been initialised yet. */
inline fun <T> xLogSafely(default: T, block: () -> T): T {
    return try {
        block()
    } catch (_: IllegalStateException) {
        default
    }
}

fun Any.xLogE(log: String) = xLogSafely { xLog().e(log) }
fun Any.xLogW(log: String) = xLogSafely { xLog().w(log) }
fun Any.xLogD(log: String) = xLogSafely { xLog().d(log) }
fun Any.xLogI(log: String) = xLogSafely { xLog().i(log) }
fun Any.xLog(logLevel: LogLevel, log: String) = xLogSafely { xLog().log(logLevel.int, log) }

fun Any.xLogE(log: String, e: Throwable) = xLogSafely { xLogStack().e(log, e) }
fun Any.xLogW(log: String, e: Throwable) = xLogSafely { xLogStack().w(log, e) }
fun Any.xLogD(log: String, e: Throwable) = xLogSafely { xLogStack().d(log, e) }
fun Any.xLogI(log: String, e: Throwable) = xLogSafely { xLogStack().i(log, e) }
fun Any.xLog(logLevel: LogLevel, log: String, e: Throwable) = xLogSafely { xLogStack().log(logLevel.int, log, e) }

fun Any.xLogE(log: Any?) = xLogSafely { xLog().let { if (log == null) it.e("null") else it.e(log) } }
fun Any.xLogW(log: Any?) = xLogSafely { xLog().let { if (log == null) it.w("null") else it.w(log) } }
fun Any.xLogD(log: Any?) = xLogSafely { xLog().let { if (log == null) it.d("null") else it.d(log) } }
fun Any.xLogI(log: Any?) = xLogSafely { xLog().let { if (log == null) it.i("null") else it.i(log) } }
fun Any.xLog(
    logLevel: LogLevel,
    log: Any?,
) = xLogSafely {
    xLog().let { if (log == null) it.log(logLevel.int, "null") else it.log(logLevel.int, log) }
}

/*fun Any.xLogE(vararg logs: Any) = xLog().e(logs)
fun Any.xLogW(vararg logs: Any) = xLog().w(logs)
fun Any.xLogD(vararg logs: Any) = xLog().d(logs)
fun Any.xLogI(vararg logs: Any) = xLog().i(logs)
fun Any.xLog(logLevel: LogLevel, vararg logs: Any) = xLog().log(logLevel.int, logs)*/

fun Any.xLogE(format: String, vararg args: Any?) = xLogSafely { xLog().e(format, *args) }
fun Any.xLogW(format: String, vararg args: Any?) = xLogSafely { xLog().w(format, *args) }
fun Any.xLogD(format: String, vararg args: Any?) = xLogSafely { xLog().d(format, *args) }
fun Any.xLogI(format: String, vararg args: Any?) = xLogSafely { xLog().i(format, *args) }
fun Any.xLog(logLevel: LogLevel, format: String, vararg args: Any) = xLogSafely { xLog().log(logLevel.int, format, *args) }

sealed class LogLevel(val int: Int, val androidLevel: Int) {
    object None : LogLevel(XLogLevel.NONE, Log.ASSERT)
    object Error : LogLevel(XLogLevel.ERROR, Log.ERROR)
    object Warn : LogLevel(XLogLevel.WARN, Log.WARN)
    object Info : LogLevel(XLogLevel.INFO, Log.INFO)
    object Debug : LogLevel(XLogLevel.DEBUG, Log.DEBUG)
    object Verbose : LogLevel(XLogLevel.VERBOSE, Log.VERBOSE)
    object All : LogLevel(XLogLevel.ALL, Log.VERBOSE)

    val name get() = getLevelName(this)
    val shortName get() = getLevelShortName(this)

    companion object {
        fun getLevelName(logLevel: LogLevel): String = XLogLevel.getLevelName(logLevel.int)
        fun getLevelShortName(logLevel: LogLevel): String = XLogLevel.getShortLevelName(logLevel.int)

        fun values() = listOf(
            None,
            Error,
            Warn,
            Info,
            Debug,
            Verbose,
            All,
        )
    }
}

@Deprecated("Use proper throwable function", ReplaceWith("""xLogE("", log)"""))
fun Any.xLogE(log: Throwable) = xLogSafely { xLogStack().e(log) }

@Deprecated("Use proper throwable function", ReplaceWith("""xLogW("", log)"""))
fun Any.xLogW(log: Throwable) = xLogSafely { xLogStack().w(log) }

@Deprecated("Use proper throwable function", ReplaceWith("""xLogD("", log)"""))
fun Any.xLogD(log: Throwable) = xLogSafely { xLogStack().d(log) }

@Deprecated("Use proper throwable function", ReplaceWith("""xLogI("", log)"""))
fun Any.xLogI(log: Throwable) = xLogSafely { xLogStack().i(log) }

@Deprecated("Use proper throwable function", ReplaceWith("""xLog(logLevel, "", log)"""))
fun Any.xLog(logLevel: LogLevel, log: Throwable) = xLogSafely { xLogStack().log(logLevel.int, log) }
