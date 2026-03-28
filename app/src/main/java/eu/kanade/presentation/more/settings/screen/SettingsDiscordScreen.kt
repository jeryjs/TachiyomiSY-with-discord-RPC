package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TriStateListDialog
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.runBlocking
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsDiscordScreen : SearchableSettings {

    private var showDiscordStatusDialog by mutableStateOf(false)

    fun requestDiscordStatusDialog() {
        showDiscordStatusDialog = true
    }

    @Composable
    fun DiscordStatusDialogHost() {
        val connectionsPreferences = remember { Injekt.get<ConnectionsPreferences>() }
        val discordRPCStatus = connectionsPreferences.discordRPCStatus()
        val status by discordRPCStatus.collectAsState()

        if (showDiscordStatusDialog) {
            DiscordStatusDialog(
                value = status,
                onDismissRequest = { showDiscordStatusDialog = false },
                onValueChange = {
                    discordRPCStatus.set(it)
                    showDiscordStatusDialog = false
                },
            )
        }
    }

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_connections

    @Composable
    override fun RowScope.AppBarAction() {
        val uriHandler = LocalUriHandler.current
        IconButton(onClick = { uriHandler.openUri("https://tachiyomi.org/help/guides/tracking/") }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = stringResource(MR.strings.tracking_guide),
            )
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val navigator = LocalNavigator.currentOrThrow
        val connectionsPreferences = remember { Injekt.get<ConnectionsPreferences>() }
        val connectionsManager = remember { Injekt.get<ConnectionsManager>() }
        val enableDRPCPref = connectionsPreferences.enableDiscordRPC()
        val useChapterTitlesPref = connectionsPreferences.useChapterTitles()
        val discordRPCStatus = connectionsPreferences.discordRPCStatus()

        val enableDRPC by enableDRPCPref.collectAsState()

        var dialog by remember { mutableStateOf<Any?>(null) }
        dialog?.run {
            when (this) {
                is LogoutConnectionsDialog -> {
                    ConnectionsLogoutDialog(
                        service = service,
                        onDismissRequest = {
                            dialog = null
                            enableDRPCPref.set(false)
                        },
                    )
                }
            }
        }

        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.discord_accounts),
                onClick = { navigator.push(DiscordAccountsScreen) },
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.connections_discord),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = enableDRPCPref,
                        title = stringResource(MR.strings.pref_enable_discord_rpc),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = useChapterTitlesPref,
                        enabled = enableDRPC,
                        title = stringResource(MR.strings.show_chapters_titles_title),
                        subtitle = stringResource(MR.strings.show_chapters_titles_subtitle),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = discordRPCStatus,
                        title = stringResource(MR.strings.pref_discord_status),
                        entries = persistentMapOf(
                            -1 to stringResource(MR.strings.pref_discord_dnd),
                            0 to stringResource(MR.strings.pref_discord_idle),
                            1 to stringResource(MR.strings.pref_discord_online),
                        ),
                        enabled = enableDRPC,
                    ),
                ),
            ),
            getRPCIncognitoGroup(
                connectionsPreferences = connectionsPreferences,
                enabled = enableDRPC,
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.logout),
                onClick = { dialog = LogoutConnectionsDialog(connectionsManager.discord) },
            ),
        )
    }

    @Composable
    private fun getRPCIncognitoGroup(
        connectionsPreferences: ConnectionsPreferences,
        enabled: Boolean,
    ): Preference.PreferenceGroup {
        val getCategories = remember { Injekt.get<GetCategories>() }
        val allCategories by getCategories.subscribe().collectAsState(initial = runBlocking { getCategories.await() })

        val discordRPCIncognitoPref = connectionsPreferences.discordRPCIncognito()
        val discordRPCIncognitoCategoriesPref = connectionsPreferences.discordRPCIncognitoCategories()

        val includedManga by discordRPCIncognitoCategoriesPref.collectAsState()
        var showDialog by rememberSaveable { mutableStateOf(false) }
        if (showDialog) {
            TriStateListDialog(
                title = stringResource(MR.strings.general_categories),
                message = stringResource(MR.strings.pref_discord_incognito_categories_details),
                items = allCategories,
                initialChecked = includedManga.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
                initialInversed = includedManga.mapNotNull { allCategories.find { false } },
                itemLabel = { it.visualName },
                onDismissRequest = { showDialog = false },
                onValueChanged = { newIncluded, _ ->
                    discordRPCIncognitoCategoriesPref.set(
                        newIncluded.fastMap { it.id.toString() }
                            .toSet(),
                    )
                    showDialog = false
                },
                onlyChecked = true,
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.general_categories),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = discordRPCIncognitoPref,
                    title = stringResource(MR.strings.pref_discord_incognito),
                    subtitle = stringResource(MR.strings.pref_discord_incognito_summary),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.general_categories),
                    subtitle = getCategoriesLabel(
                        allCategories = allCategories,
                        included = includedManga,
                    ),
                    onClick = { showDialog = true },
                ),
                Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_discord_incognito_categories_details)),
            ),
            enabled = enabled,
        )
    }

    @Composable
    private fun DiscordStatusDialog(
        value: Int,
        onDismissRequest: () -> Unit,
        onValueChange: (Int) -> Unit,
    ) {
        val entries = persistentMapOf(
            -1 to stringResource(MR.strings.pref_discord_dnd),
            0 to stringResource(MR.strings.pref_discord_idle),
            1 to stringResource(MR.strings.pref_discord_online),
        )

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(text = stringResource(MR.strings.pref_discord_status)) },
            text = {
                Column {
                    entries.forEach { current ->
                        DiscordStatusDialogRow(
                            label = current.value,
                            isSelected = value == current.key,
                            onSelected = { onValueChange(current.key) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    @Composable
    private fun DiscordStatusDialogRow(
        label: String,
        isSelected: Boolean,
        onSelected: () -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .selectable(
                    selected = isSelected,
                    onClick = { if (!isSelected) onSelected() },
                )
                .fillMaxWidth()
                .minimumInteractiveComponentSize(),
        ) {
            RadioButton(selected = isSelected, onClick = null)
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.merge(),
                modifier = Modifier.padding(start = 24.dp),
            )
        }
    }
}
