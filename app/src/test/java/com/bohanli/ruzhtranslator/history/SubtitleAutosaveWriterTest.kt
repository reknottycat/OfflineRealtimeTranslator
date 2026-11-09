package com.bohanli.ruzhtranslator.history

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAutosaveWriterTest {
    @Test fun russianIsSavedBeforeTranslationAndLaterUpdatesSameRow() {
        val insertions = AtomicInteger(0)
        val events = CopyOnWriteArrayList<String>()
        val saved = CountDownLatch(3)
        val store = object : SubtitleAutosaveWriter.Store {
            override suspend fun insert(russian: String, chinese: String): Long {
                insertions.incrementAndGet()
                events.add("insert:$russian:$chinese")
                return 12L
            }
            override suspend fun update(id: Long, russian: String, chinese: String) {
                assertEquals(12L, id)
                events.add("update:$russian:$chinese")
            }
        }
        val writer = SubtitleAutosaveWriter(store,
            onSaved = { saved.countDown() },
            onFailure = { throw AssertionError(it) },
            callbackDispatcher = Dispatchers.Unconfined)
        assertTrue(writer.capture("Привет", ""))
        assertTrue(writer.capture("Привет", "你好"))
        assertTrue(writer.capture("Привет\nКак дела", "你好"))
        writer.close()
        assertTrue("Queued writes must complete after stopping", saved.await(10, TimeUnit.SECONDS))
        assertEquals(1, insertions.get())
        assertEquals(3, events.size)
        assertEquals("insert:Привет:", events[0])
        assertEquals("update:Привет:你好", events[1])
        assertEquals("update:Привет\nКак дела:你好", events[2])
    }

    @Test fun emptyDuplicateAndClosedSnapshotsAreHandled() {
        val saved = CountDownLatch(1)
        val store = object : SubtitleAutosaveWriter.Store {
            override suspend fun insert(russian: String, chinese: String): Long = 77L
            override suspend fun update(id: Long, russian: String, chinese: String) {
                throw AssertionError("Duplicate must not update")
            }
        }
        val writer = SubtitleAutosaveWriter(store,
            onSaved = { saved.countDown() },
            onFailure = { throw AssertionError(it) },
            callbackDispatcher = Dispatchers.Unconfined)
        assertFalse(writer.capture("", ""))
        assertTrue(writer.capture("один", ""))
        assertTrue(writer.capture("один", ""))
        writer.close()
        assertTrue(saved.await(10, TimeUnit.SECONDS))
        assertFalse(writer.capture("два", ""))
    }
}
