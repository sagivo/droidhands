package com.droidhands.core.plugins

import com.droidhands.core.tools.ToolException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An app action failed because the app did not do what its recipe expects: no activity of this app version accepts the
 * intent or link, or a screen recipe didn't find what it needs. Platforms throw it (instead of a plain
 * [ToolException]) only for such integration failures, never for refusals (app access, a locked phone, Pause), so
 * [ActionHealth] learns which actions don't work on which app versions.
 */
open class IntegrationException(message: String) : ToolException(message)

/** Persists [ActionHealth] (a small JSON document). Implementations must be thread-safe; failures are ignored. */
interface ActionHealthStore {
    fun load(): String?

    fun save(json: String)
}

/**
 * How each app action has fared on each installed app version on this phone. Apps change between versions and
 * phones, so instead of a hand-kept compatibility list DroidHands records what happened: an action whose recipe failed
 * on this version is reported to agents (`app_actions_list` `health`, and the error of the next failure) so they go
 * straight to phone use, and a success clears the record. A new app version starts clean. Thread-safe.
 */
class ActionHealth(private val store: ActionHealthStore? = null, private val clock: () -> Long = System::currentTimeMillis) {
    /** One action's record on one app version. */
    @Serializable
    data class Record(
        val pkg: String,
        val version: String,
        val action: String,
        /** Failures in a row since the last success. */
        val failures: Int,
        val lastError: String,
        val lastFailureAt: Long,
    ) {
        /** Two or more failures in a row: the action most likely doesn't work with this app version. */
        val failing: Boolean get() = failures >= FAILING_AFTER
    }

    @Serializable
    private data class Document(val records: List<Record> = emptyList())

    private val lock = Any()
    private val records = LinkedHashMap<Triple<String, String, String>, Record>(64, 0.75f, true)
    private var loaded = false

    /** The failure record of [actionId] on [app]'s installed version, or null if it has not failed since its last success. */
    fun status(app: InstalledApp, actionId: String): Record? = synchronized(lock) {
        ensureLoaded()
        records[key(app, actionId)]
    }

    fun failed(app: InstalledApp, actionId: String, message: String): Record {
        val record = synchronized(lock) {
            ensureLoaded()
            val k = key(app, actionId)
            val previous = records[k]
            Record(app.packageName, k.second, actionId, (previous?.failures ?: 0) + 1, message.take(MAX_ERROR), clock()).also {
                records[k] = it
                trim()
            }
        }
        persist()
        return record
    }

    fun succeeded(app: InstalledApp, actionId: String) {
        val removed = synchronized(lock) {
            ensureLoaded()
            records.remove(key(app, actionId)) != null
        }
        if (removed) persist()
    }

    /** The sentence added to an action's error once it has failed on this app version. */
    fun failureNote(app: InstalledApp, record: Record, phoneUse: Boolean = true): String =
        "(This action has now failed ${times(record.failures)} on ${app.label} ${versionLabel(app)} on this phone: the app " +
            "probably changed. " +
            (if (phoneUse) "Do it with phone use instead: screen_observe, then ui_tap / ui_type.)" else "Ask the user to do it on the phone instead.)")

    private fun times(n: Int) = if (n == 1) "once" else "$n times in a row"

    /** "version 2.24.1" or "this version". */
    fun versionLabel(app: InstalledApp): String = app.versionName?.let { "version $it" } ?: "this version"

    private fun key(app: InstalledApp, actionId: String) =
        Triple(app.packageName, app.versionCode?.toString() ?: app.versionName ?: "?", actionId)

    private fun trim() {
        while (records.size > MAX_RECORDS) records.remove(records.keys.first())
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = try {
            store?.load()
        } catch (e: Exception) {
            null
        } ?: return
        val doc = try {
            JSON.decodeFromString(Document.serializer(), text)
        } catch (e: Exception) {
            return
        }
        for (r in doc.records.takeLast(MAX_RECORDS)) records[Triple(r.pkg, r.version, r.action)] = r
    }

    private fun persist() {
        val store = store ?: return
        val json = synchronized(lock) { JSON.encodeToString(Document.serializer(), Document(records.values.toList())) }
        try {
            store.save(json)
        } catch (e: Exception) {
            // Best effort: the in-memory record still applies.
        }
    }

    companion object {
        const val FAILING_AFTER = 2
        const val MAX_RECORDS = 500
        private const val MAX_ERROR = 300
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** [ActionHealthStore] in one JSON file, replaced atomically. */
class FileActionHealthStore(private val file: java.io.File) : ActionHealthStore {
    private val lock = Any()

    override fun load(): String? = synchronized(lock) { if (file.isFile) file.readText() else null }

    override fun save(json: String) = synchronized(lock) { com.droidhands.core.util.AtomicFiles.write(file, json.toByteArray()) }
}
