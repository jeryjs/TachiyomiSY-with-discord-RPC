package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * SY-specific file
 */
@Serializable
data class BackupFeed(
    @ProtoNumber(1) val source: Long = 0,
    @ProtoNumber(2) val global: Boolean = true,
    @ProtoNumber(3) val savedSearchName: String? = null,
)

val backupFeedMapper =
    { source: Long, global: Boolean, name: String? ->
        BackupFeed(
            source = source,
            global = global,
            savedSearchName = name,
        )
    }
