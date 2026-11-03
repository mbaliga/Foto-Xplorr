package com.fotoxplorr.app.sync

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

enum class SyncDestinationKind(val code: String) {
    /** A folder picked with the system folder picker. Works in both builds. */
    FOLDER("folder"),

    /** WebDAV: Nextcloud, ownCloud, a NAS. Connect build only. */
    WEBDAV("webdav"),

    /** S3-compatible storage. Connect build only. */
    S3("s3"),
    ;

    val needsNetwork: Boolean get() = this != FOLDER

    companion object {
        fun fromCode(code: String?): SyncDestinationKind? = entries.firstOrNull { it.code == code }
    }
}

/** Passphrase-encryption parameters of a destination. Salt and iterations are not secret; the passphrase is never stored. */
data class SyncEncryptionSettings(
    val saltHex: String,
    val iterations: Int,
    /** A hash that proves a typed passphrase is the one the set was started with (see [SyncEncryption]). */
    val keyCheckHex: String,
)

/**
 * Everything about the one destination, EXCEPT its secret (WebDAV password, S3 secret key), which is
 * in [SyncSecrets]. Plain text on disk ([SyncConfigCodec]); contains addresses and user names, so it is
 * app-private and never logged.
 */
data class SyncDestinationConfig(
    /** Names this destination's manifest and secret; a new destination gets a new id, hence a fresh manifest. */
    val id: String,
    val kind: SyncDestinationKind,
    val displayName: String,
    val folderTreeUri: String? = null,
    val webDav: WebDavSettings? = null,
    val s3: S3Settings? = null,
    val selection: SyncSelection = SyncSelection(),
    val wifiOnly: Boolean = true,
    /** Most bytes one run may send, or null for no limit. */
    val byteCapPerRun: Long? = null,
    /** Null means files are sent as they are. */
    val encryption: SyncEncryptionSettings? = null,
    val encryptEnabled: Boolean = false,
)

object SyncConfigCodec {
    fun encode(c: SyncDestinationConfig): String = buildString {
        fun put(key: String, value: String?) {
            if (value != null) append(key).append('=').append(SyncManifestCodec.escape(value)).append('\n')
        }
        put("version", "1")
        put("id", c.id)
        put("kind", c.kind.code)
        put("name", c.displayName)
        put("folder", c.folderTreeUri)
        c.webDav?.let {
            put("webdav.url", it.baseUrl)
            put("webdav.user", it.username)
            put("webdav.plainhttp", it.allowPlainHttpOnLocal.toString())
        }
        c.s3?.let {
            put("s3.endpoint", it.endpoint)
            put("s3.region", it.region)
            put("s3.bucket", it.bucket)
            put("s3.access", it.accessKeyId)
            put("s3.pathstyle", it.pathStyle.toString())
            put("s3.prefix", it.prefix)
            put("s3.plainhttp", it.allowPlainHttpOnLocal.toString())
        }
        put("sel.all", c.selection.allPhotos.toString())
        put("sel.fav", c.selection.favourites.toString())
        put("sel.videos", c.selection.includeVideos.toString())
        c.selection.folderKeys.sorted().forEach { put("sel.folder", it) }
        put("wifionly", c.wifiOnly.toString())
        put("cap", c.byteCapPerRun?.toString())
        put("encrypt", c.encryptEnabled.toString())
        c.encryption?.let {
            put("enc.salt", it.saltHex)
            put("enc.iter", it.iterations.toString())
            put("enc.check", it.keyCheckHex)
        }
    }

    /** Null when the text is not a usable configuration (no id, unknown kind). Unknown keys are ignored. */
    fun decode(text: String): SyncDestinationConfig? {
        val single = HashMap<String, String>()
        val folders = LinkedHashSet<String>()
        for (line in text.split('\n')) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            val value = SyncManifestCodec.unescape(line.substring(eq + 1))
            if (key == "sel.folder") folders += value else single[key] = value
        }
        val id = single["id"]?.takeIf { it.isNotBlank() } ?: return null
        val kind = SyncDestinationKind.fromCode(single["kind"]) ?: return null
        fun flag(key: String, default: Boolean) = single[key]?.lowercase(Locale.ROOT)?.let { it == "true" } ?: default
        val webDav = single["webdav.url"]?.let {
            WebDavSettings(it, single["webdav.user"].orEmpty(), flag("webdav.plainhttp", false))
        }
        val s3 = single["s3.bucket"]?.let {
            S3Settings(
                endpoint = single["s3.endpoint"].orEmpty(),
                region = single["s3.region"].orEmpty(),
                bucket = it,
                accessKeyId = single["s3.access"].orEmpty(),
                pathStyle = flag("s3.pathstyle", true),
                prefix = single["s3.prefix"].orEmpty(),
                allowPlainHttpOnLocal = flag("s3.plainhttp", false),
            )
        }
        val encryption = if (single["enc.salt"] != null && single["enc.iter"]?.toIntOrNull() != null && single["enc.check"] != null) {
            SyncEncryptionSettings(single["enc.salt"]!!, single["enc.iter"]!!.toInt(), single["enc.check"]!!)
        } else {
            null
        }
        return SyncDestinationConfig(
            id = id,
            kind = kind,
            displayName = single["name"].orEmpty(),
            folderTreeUri = single["folder"],
            webDav = webDav,
            s3 = s3,
            selection = SyncSelection(
                allPhotos = flag("sel.all", false),
                folderKeys = folders,
                favourites = flag("sel.fav", false),
                includeVideos = flag("sel.videos", true),
            ),
            wifiOnly = flag("wifionly", true),
            byteCapPerRun = single["cap"]?.toLongOrNull()?.takeIf { it > 0 },
            encryption = encryption,
            encryptEnabled = flag("encrypt", false),
        )
    }
}

interface SyncConfigStore {
    fun load(): SyncDestinationConfig?
    fun save(config: SyncDestinationConfig)

    /** Forgets the destination. Remote files and the manifest file are not touched. */
    fun clear()
}

class FileSyncConfigStore(private val file: File) : SyncConfigStore {
    override fun load(): SyncDestinationConfig? =
        if (file.exists()) runCatching { SyncConfigCodec.decode(file.readText(Charsets.UTF_8)) }.getOrNull() else null

    override fun save(config: SyncDestinationConfig) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use {
            it.write(SyncConfigCodec.encode(config).toByteArray(Charsets.UTF_8))
            it.flush()
            it.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("The sync settings could not be saved")
        }
    }

    override fun clear() {
        file.delete()
    }
}

/** Secrets of destinations, encrypted at rest on the phone (the Android adapter is [KeystoreSyncSecrets]). Never logged. */
interface SyncSecrets {
    fun get(id: String): CharArray?
    fun put(id: String, value: CharArray)
    fun remove(id: String)
}
