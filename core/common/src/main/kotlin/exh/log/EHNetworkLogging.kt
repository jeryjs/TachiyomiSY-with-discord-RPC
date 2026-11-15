package exh.log

import com.elvishew.xlog.XLog
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/**
 * SY-specific file
 */
fun OkHttpClient.Builder.maybeInjectEHLogger(): OkHttpClient.Builder = xLogSafely(this@maybeInjectEHLogger) {
    if (EHLogLevel.shouldLog(EHLogLevel.EXTREME)) {
        val xlogBorder = XLog.tag("||EH-NETWORK-JSON").build()
        val xlogNoBorder = XLog.tag("||EH-NETWORK-JSON").disableBorder().build()
        val logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger { message ->
            try {
                Json.decodeFromString<Any>(message)
                xlogBorder.json(message)
            } catch (ex: Exception) {
                xlogNoBorder.d(message)
            }
        }
        return@xLogSafely addInterceptor(HttpLoggingInterceptor(logger).apply { level = HttpLoggingInterceptor.Level.BODY })
    }
    this@maybeInjectEHLogger
}
