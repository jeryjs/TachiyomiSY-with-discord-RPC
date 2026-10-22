package mihon.app.di.injekt

import android.app.Application
import android.content.Context
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import exh.source.ExhPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.app.di.AppGraph
import mihon.core.metro.GraphProvider
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.TypeReference
import java.lang.reflect.Type

class MetroInjektRegistrar(
    private val application: Application,
    private val graphProvider: GraphProvider<AppGraph>,
) : InjektRegistrar {

    private val graph: AppGraph inline get() = graphProvider.graph

    private val bindings = mapOf<Type, () -> Any>(
        Application::class.java to { application },
        Context::class.java to { application },

        Json::class.java to { graph.json },
        ProtoBuf::class.java to { graph.protoBuf },
        XML::class.java to { graph.xml },

        NetworkHelper::class.java to { graph.networkHelper },
        JavaScriptEngine::class.java to { graph.javaScriptEngine },
        // SY -->
        // The fork still resolves these through Injekt, so expose them from the graph.
        ExhPreferences::class.java to { graph.exhPreferences },
        ExtensionManager::class.java to { graph.extensionManager },
        GetCategories::class.java to { graph.getCategories },
        GetIncognitoState::class.java to { graph.getIncognitoState },
        PreferenceStore::class.java to { graph.preferenceStore },
        SourcePreferences::class.java to { graph.sourcePreferences },
        ConnectionsManager::class.java to { graph.connectionsManager },
        ConnectionsPreferences::class.java to { graph.connectionsPreferences },
        // SY <--
    )

    override fun <R : Any> getInstance(forType: Type): R = getInstanceOrNull(forType)
        ?: throw Exception("$forType is not exposed to Injekt, add it to ${this::class.simpleName}")

    override fun <R : Any> getInstanceOrElse(forType: Type, default: R): R = getInstanceOrNull(forType) ?: default

    override fun <R : Any> getInstanceOrElse(forType: Type, default: () -> R): R =
        getInstanceOrNull(forType) ?: default()

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrNull(forType: Type): R? = bindings[forType]?.invoke() as R?

    override fun <R : Any, K : Any> getKeyedInstance(forType: Type, key: K): R = unsupported("Keyed injection")

    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: R): R =
        unsupported("Keyed injection")

    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: () -> R): R =
        unsupported("Keyed injection")

    @Suppress("RedundantNullableReturnType")
    override fun <R : Any, K : Any> getKeyedInstanceOrNull(forType: Type, key: K): R? = unsupported("Keyed injection")

    override fun <T : Any> hasFactory(forType: TypeReference<T>): Boolean = forType.type in bindings

    private fun readOnly(): Nothing = throw UnsupportedOperationException("Mihon's Injekt instance is read-only")

    private fun unsupported(feature: String): Nothing =
        throw UnsupportedOperationException("$feature is not supported by Mihon's Injekt instance")
}
