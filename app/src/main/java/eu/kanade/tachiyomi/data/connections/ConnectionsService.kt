package eu.kanade.tachiyomi.data.connections

import androidx.annotation.CallSuper
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

abstract class ConnectionsService(val id: Long) {

    val connectionsPreferences: ConnectionsPreferences by injectLazy()
    private val networkService: NetworkHelper by injectLazy()

    open val client: OkHttpClient
        get() = networkService.client

    // Name of the connection service to display
    abstract fun nameRes(): StringResource

    @DrawableRes
    abstract fun getLogo(): Int

    @ColorInt
    abstract fun getLogoColor(): Int

    @CallSuper
    open fun logout() {
        connectionsPreferences.setConnectionsCredentials(this, "", "")
        connectionsPreferences.connectionsToken(this).set("")
    }

    abstract suspend fun login(username: String, password: String)

    fun getUsername() = connectionsPreferences.connectionsUsername(this).get()

    fun getPassword() = connectionsPreferences.connectionsPassword(this).get()

    fun saveCredentials(username: String, password: String) {
        connectionsPreferences.setConnectionsCredentials(this, username, password)
    }

    open val isLogged: Boolean
        get() = getUsername().isNotEmpty() &&
            getPassword().isNotEmpty()
}
