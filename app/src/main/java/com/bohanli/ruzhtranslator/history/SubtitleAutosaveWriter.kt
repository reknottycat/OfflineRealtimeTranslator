package com.bohanli.ruzhtranslator.history

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Append-only snapshots queued to a single IO worker per listening session.
 *
 * Each recording owns exactly one Room row: initial ASR text inserts it,
 * subsequent ASR finals and delayed translations update that same row.
 * No activity lifecycle coroutine is used, so navigating away does not
 * cancel pending disk writes. Only finished segments are persisted.
 *
 * `close()` drains accepted snapshots and terminates its worker; it does
 * not delete the saved row or wait on Android's UI thread.
 */
class SubtitleAutosaveWriter(
    private val store: Store,
    private val onSaved: (Long) -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    data class Snapshot(val russian: String, val chinese: String)

    interface Store {
        suspend fun insert(russian: String, chinese: String): Long
        suspend fun update(id: Long, russian: String, chinese: String)
    }

    constructor(
        dao: TranslationDao,
        onSaved: (Long) -> Unit = {},
        onFailure: (Throwable) -> Unit = {}
    ) : this(object : Store {
        override suspend fun insert(russian: String, chinese: String): Long =
            dao.insert(TranslationRecord(ruText = russian, zhText = chinese))
        override suspend fun update(id: Long, russian: String, chinese: String) =
            dao.updateSubtitleText(id.toInt(), russian, chinese)
    }, onSaved, onFailure, Dispatchers.Main)
    private val snapshots = Channel<Snapshot>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var previousQueued: Snapshot? = null

    init {
        scope.launch {
            var rowId: Long? = null
            var previousSaved: Snapshot? = null
            for (snapshot in snapshots) {
                if (snapshot == previousSaved) continue
                if (snapshot.russian.isBlank() && snapshot.chinese.isBlank()) continue
                try {
                    val id = rowId
                    if (id == null) {
                        rowId = store.insert(snapshot.russian, snapshot.chinese)
                    } else {
                        store.update(id, snapshot.russian, snapshot.chinese)
                    }
                    previousSaved = snapshot
                    withContext(callbackDispatcher) { onSaved(rowId ?: 0L) }
                } catch (error: Exception) {
                    Log.e("SubtitleAutosave", "Subtitle persistence failed", error)
                    withContext(callbackDispatcher) { onFailure(error) }
                }
            }
        }
    }

    /** Main-thread producer; returns true if the snapshot entered the durability queue. */
    fun capture(russian: String, chinese: String): Boolean {
        val snapshot = Snapshot(russian, chinese)
        if (snapshot.russian.isBlank() && snapshot.chinese.isBlank()) return false
        if (snapshot == previousQueued) return true
        if (snapshots.trySend(snapshot).isFailure) return false
        previousQueued = snapshot
        return true
    }

    /** Finish this session, keeping all accepted snapshots until they reach Room. */
    fun close() {
        snapshots.close()
    }
}
