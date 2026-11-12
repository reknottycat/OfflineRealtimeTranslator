package com.bohanli.ruzhtranslator.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Production HTTP loop and real local HTTP streams; no external downloads or Android mocks. */
class ModelDownloadTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val data = ByteArray(53) { (it * 7 + 3).toByte() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun spec(bytes: ByteArray = data) = ModelCatalog.GEMMA4.copy(
        engine = "transport-test", directory = "test", fileName = "test.gguf",
        bytes = bytes.size.toLong(), maximumBytes = bytes.size.toLong(), sha256 = hash(bytes))
    private fun partial() = File(temporary.newFolder(), "test.gguf.part")
    private fun journal(partial: File) = File(partial.parentFile, "test.gguf.chunks.done")
    private fun url(path: String) = "https://$path.test/$path"
    private fun transport(server: TestServer, partial: File, bytes: ByteArray = data,
                          workers: Int = 1, attempts: Int = 3, readTimeoutMs: Int = 1_000) = ModelDownloadTransport(
        spec(bytes), partial, chunkBytes = 8, workers = workers, attemptsPerChunk = attempts,
        connectTimeoutMs = 1_000, readTimeoutMs = readTimeoutMs, handshakeTimeoutMs = 2_000,
        openConnection = server::open)

    @Test fun exactRangesAreSavedAndTheCompleteFileIsVerified() = runBlocking {
        TestServer { binary(it, data) }.use { server ->
            val file = partial()
            val progress = CopyOnWriteArrayList<ModelDownloadTransport.Progress>()
            transport(server, file, workers = 4).download(listOf(url("good")), progress::add)
            assertArrayEquals(data, file.readBytes())
            assertEquals((0 until 7).map { "bytes=${it * 8}-${minOf(it * 8 + 7, 52)}" }.toSet(), server.requests.map { it.range }.toSet())
            assertEquals(8, journal(file).readLines().size)
            assertTrue(progress.all { it.received in 0..data.size.toLong() && it.expected == data.size.toLong() })
            val verification = progress.filter { it.stage == "校验完整模型 SHA-256" }
            assertEquals(listOf(0L, data.size.toLong()), verification.map { it.received })
            assertTrue(server.requests.all { it.method == "GET" })
        }
    }

    @Test fun proxyHtmlWith206AndPlausibleRangeIsRejectedOnceThenFallsBack() = runBlocking {
        TestServer { exchange ->
            if (exchange.requestURI.path == "/bad") {
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.responseHeaders.add("Content-Range", "bytes 0-7/195")
                send(exchange, 206, "Site Una".toByteArray())
            } else binary(exchange, data)
        }.use { server ->
            val file = partial()
            val progress = mutableListOf<ModelDownloadTransport.Progress>()
            transport(server, file).download(listOf(url("bad"), url("good")), progress::add)
            assertArrayEquals(data, file.readBytes())
            assertEquals(1, server.requests.count { it.path == "/bad" })
            assertTrue(progress.any { it.stage.contains("网页") && it.stage.contains("备用源") })
        }
    }

    @Test fun wrongContentRangeIsRejectedBeforeWritingAnInterval() = runBlocking {
        TestServer { exchange ->
            if (exchange.requestURI.path == "/wrong") {
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.responseHeaders.add("Content-Range", "bytes 1-8/53")
                send(exchange, 206, ByteArray(8) { 127 })
            } else binary(exchange, data)
        }.use { server ->
            val file = partial()
            transport(server, file).download(listOf(url("wrong"), url("good"))) {}
            assertArrayEquals(data, file.readBytes())
            assertEquals(1, server.requests.count { it.path == "/wrong" })
        }
    }

    @Test fun shortResponseDoesNotEnterJournalAndResumeFetchesItAgain() = runBlocking {
        TestServer { exchange ->
            if (exchange.requestURI.path == "/short") {
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.responseHeaders.add("Content-Range", "bytes 0-7/53")
                exchange.sendResponseHeaders(206, 8)
                exchange.responseBody.use { it.write(data, 0, 2) }
            } else binary(exchange, data)
        }.use { server ->
            val file = partial()
            expectFailure { transport(server, file, attempts = 1).download(listOf(url("short"))) {} }
            assertEquals(1, journal(file).readLines().size)
            assertEquals(data.size.toLong(), file.length())
            transport(server, file).download(listOf(url("good"))) {}
            assertTrue(server.requests.any { it.path == "/good" && it.range == "bytes=0-7" })
            assertArrayEquals(data, file.readBytes())
        }
    }

    @Test fun noRangeServerUsesSafeSequentialFallbackWithoutDuplicatingTheFile() = runBlocking {
        TestServer { send(it, 200, data) }.use { server ->
            val file = partial()
            transport(server, file).download(listOf(url("noRange"))) {}
            assertArrayEquals(data, file.readBytes())
            assertEquals(listOf("bytes=0-7", null), server.requests.map { it.range })
            assertFalse(journal(file).exists())
        }
    }

    @Test fun noRangeSourceStillWorksWhenAllRangeAlternativesTimeOut() = runBlocking {
        TestServer { exchange ->
            if (exchange.requestURI.path == "/stall") { Thread.sleep(500); send(exchange, 503, byteArrayOf()) }
            else send(exchange, 200, data)
        }.use { server ->
            val file = partial()
            transport(server, file, attempts = 2, readTimeoutMs = 100).download(listOf(url("noRange"), url("stall"))) {}
            assertArrayEquals(data, file.readBytes())
            assertTrue(server.requests.any { it.path == "/noRange" && it.range == null })
        }
    }

    @Test fun processRestartRehashesSavedIntervalsAndDownloadsOnlyMissingOrTornOnes() = runBlocking {
        TestServer { exchange ->
            val start = exchange.requestHeaders.getFirst("Range")?.substringAfter('=')?.substringBefore('-')?.toLong() ?: 0
            if (exchange.requestURI.path == "/interrupted" && start >= 16) send(exchange, 503, byteArrayOf())
            else binary(exchange, data)
        }.use { server ->
            val file = partial()
            expectFailure { transport(server, file, attempts = 1).download(listOf(url("interrupted"))) {} }
            assertEquals(3, journal(file).readLines().size)
            RandomAccessFile(file, "rw").use { it.seek(8); it.write(99); it.seek(16); it.write(data, 16, 8) }
            transport(server, file).download(listOf(url("resumed"))) {}
            val ranges = server.requests.filter { it.path == "/resumed" }.map { it.range }
            assertFalse(ranges.contains("bytes=0-7"))
            assertTrue(ranges.contains("bytes=8-15"))
            assertTrue(ranges.contains("bytes=16-23"))
            assertEquals(6, ranges.size)
            assertArrayEquals(data, file.readBytes())
        }
    }

    @Test fun journalForDifferentModelCannotReuseAnyOfItsIntervals() = runBlocking {
        val replacement = data.map { (it + 1).toByte() }.toByteArray()
        TestServer { binary(it, if (it.requestURI.path == "/old") data else replacement) }.use { server ->
            val file = partial()
            transport(server, file).download(listOf(url("old"))) {}
            transport(server, file, replacement).download(listOf(url("new"))) {}
            assertEquals(7, server.requests.count { it.path == "/new" })
            assertArrayEquals(replacement, file.readBytes())
            assertTrue(journal(file).readLines().first().contains(hash(replacement)))
        }
    }

    @Test fun wholeHashFailureClearsPoisonedChunksAndRetriesOtherSourceCleanly() = runBlocking {
        val corrupt = ByteArray(data.size) { 12 }
        val file = partial()
        TestServer { exchange ->
            if (exchange.requestURI.path == "/poison") binary(exchange, corrupt)
            else {
                assertFalse(journal(file).exists())
                assertEquals(0L, file.length())
                binary(exchange, data)
            }
        }.use { server ->
            transport(server, file).download(listOf(url("poison"), url("clean"))) {}
            assertArrayEquals(data, file.readBytes())
            assertEquals(7, server.requests.count { it.path == "/poison" && it.range != null })
            assertEquals(1, server.requests.count { it.path == "/poison" && it.range == null })
            assertEquals(1, server.requests.count { it.path == "/clean" && it.range == null })
            assertFalse(journal(file).exists())
        }
    }

    @Test fun badLegacyPrefixIsRecoveredEvenWhenOnlyOneHealthySourceRemains() = runBlocking {
        TestServer { binary(it, data) }.use { server ->
            val file = partial()
            file.writeBytes(ByteArray(8) { 99 })
            transport(server, file).download(listOf(url("only"))) {}
            assertArrayEquals(data, file.readBytes())
            assertTrue(server.requests.any { it.range == null })
            assertFalse(journal(file).exists())
        }
    }

    @Test fun readTimeoutMovesToHealthySource() = runBlocking {
        TestServer { exchange ->
            if (exchange.requestURI.path == "/stall") {
                exchange.responseHeaders.add("Content-Range", "bytes 0-7/53")
                exchange.sendResponseHeaders(206, 8)
                Thread.sleep(500)
                exchange.responseBody.use { it.write(data, 0, 8) }
            } else binary(exchange, data)
        }.use { server ->
            val file = partial()
            val progress = mutableListOf<ModelDownloadTransport.Progress>()
            transport(server, file, readTimeoutMs = 100).download(listOf(url("stall"), url("good")), progress::add)
            assertArrayEquals(data, file.readBytes())
            assertEquals(1, server.requests.count { it.path == "/stall" })
            assertTrue(progress.any { it.stage.contains("超时") })
        }
    }

    @Test fun httpsRedirectPreservesRequestedRange() = runBlocking {
        TestServer { exchange ->
            if (exchange.requestURI.path == "/redirect") {
                exchange.responseHeaders.add("Location", url("target"))
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else binary(exchange, data)
        }.use { server ->
            val file = partial()
            transport(server, file).download(listOf(url("redirect"))) {}
            assertArrayEquals(data, file.readBytes())
            assertEquals(server.requests.filter { it.path == "/redirect" }.map { it.range },
                server.requests.filter { it.path == "/target" }.map { it.range })
        }
    }

    @Test fun cancellationClosesAllConcurrentConnectionsWithoutTryingBackup() = runBlocking {
        val file = partial()
        val began = CountDownLatch(4)
        val connections = CopyOnWriteArrayList<BlockingConnection>()
        val download = ModelDownloadTransport(spec(), file, chunkBytes = 8, workers = 4,
            openConnection = { incoming -> BlockingConnection(incoming, began, data.size).also(connections::add) })
        val task = launch(Dispatchers.Default) { download.download(listOf(url("blocked"), url("backup"))) {} }
        assertTrue(withContext(Dispatchers.IO) { began.await(3, TimeUnit.SECONDS) })
        withTimeout(2_000) { task.cancelAndJoin() }
        assertEquals(4, connections.size)
        assertTrue(connections.all { it.closed.get() })
        assertEquals(1, journal(file).readLines().size)
    }

    @Test fun cancellationBeforeConnectionRegistrationNeverStartsItsResponse() = runBlocking {
        val file = partial()
        val enteredFactory = CountDownLatch(1)
        val returnConnection = CountDownLatch(1)
        val connection = BlockingConnection(URL(url("late")), CountDownLatch(1), data.size)
        val download = ModelDownloadTransport(spec(), file, chunkBytes = 8, workers = 1,
            openConnection = {
                enteredFactory.countDown()
                // Simulate a connection factory which does not respond to thread interruption.
                while (returnConnection.count > 0) {
                    try { returnConnection.await() } catch (_: InterruptedException) {}
                }
                connection
            })
        val task = launch(Dispatchers.Default) { download.download(listOf(url("late"))) {} }
        assertTrue(withContext(Dispatchers.IO) { enteredFactory.await(3, TimeUnit.SECONDS) })
        task.cancel()
        returnConnection.countDown()
        withTimeout(2_000) { task.join() }
        assertEquals(0, connection.responseReads.get())
        assertTrue(connection.closed.get())
    }

    @Test fun rangeIgnoredAfterResumeRestartsSequentialFileInsteadOfAppending() = runBlocking {
        TestServer { send(it, 200, data) }.use { server ->
            val file = partial()
            file.writeBytes(data.copyOfRange(0, 8))
            transport(server, file).download(listOf(url("noRange"))) {}
            assertArrayEquals(data, file.readBytes())
            assertEquals(listOf("bytes=8-15", "bytes=8-"), server.requests.map { it.range })
        }
    }

    @Test fun handshakeExpiredDuringConnectionFactoryNeverStartsAResponse() = runBlocking {
        val file = partial()
        val connection = BlockingConnection(URL(url("late")), CountDownLatch(1), data.size)
        val download = ModelDownloadTransport(spec(), file, chunkBytes = 8, workers = 1,
            attemptsPerChunk = 1, handshakeTimeoutMs = 50,
            openConnection = { Thread.sleep(150); connection })
        expectFailure { download.download(listOf(url("late"))) {} }
        assertEquals(0, connection.responseReads.get())
        assertTrue(connection.closed.get())
    }

    @Test fun sequential416RestartsOnlyOnceAndStillVerifiesTheModel() = runBlocking {
        TestServer { exchange ->
            if (exchange.requestHeaders.getFirst("Range") == "bytes=8-") exchange.sendResponseHeaders(416, -1)
            else send(exchange, 200, data)
        }.use { server ->
            val file = partial()
            file.writeBytes(data.copyOfRange(0, 8))
            transport(server, file).download(listOf(url("noRange"))) {}
            assertArrayEquals(data, file.readBytes())
            assertEquals(listOf("bytes=8-15", "bytes=8-", null), server.requests.map { it.range })
        }
    }

    @Test fun failingChunkIsBoundedByConfiguredAttemptsAndLeavesNoCompletedEntry() = runBlocking {
        TestServer { send(it, 503, byteArrayOf()) }.use { server ->
            val file = partial()
            expectFailure { transport(server, file, attempts = 2).download(listOf(url("failed"))) {} }
            assertEquals(2, server.requests.size)
            assertEquals(1, journal(file).readLines().size)
        }
    }

    @Test fun redirectToUnencryptedHttpIsNeverOpened() = runBlocking {
        val opened = CopyOnWriteArrayList<String>()
        TestServer { exchange ->
            exchange.responseHeaders.add("Location", "http://untrusted.test/file")
            exchange.sendResponseHeaders(302, -1)
        }.use { server ->
            val file = partial()
            val download = ModelDownloadTransport(spec(), file, chunkBytes = 8, workers = 1, attemptsPerChunk = 1,
                openConnection = { incoming -> opened += incoming.protocol; server.open(incoming) })
            expectFailure { download.download(listOf(url("redirect"))) {} }
            assertEquals(listOf("https"), opened)
            assertEquals(1, journal(file).readLines().size)
        }
    }

    @Test fun rangeFailureJoinsAllSiblingConnectionsBeforeSequentialFileReset() = runBlocking {
        val file = partial()
        val allRangeRequests = CountDownLatch(4)
        val connections = CopyOnWriteArrayList<HttpURLConnection>()
        val closedFlags = CopyOnWriteArrayList<AtomicBoolean>()
        val siblingsClosedBeforeFallback = AtomicBoolean()
        val download = ModelDownloadTransport(spec(), file, chunkBytes = 8, workers = 4,
            openConnection = { incoming ->
                val sequential = connections.size >= 4
                if (sequential) siblingsClosedBeforeFallback.set(closedFlags.all { it.get() })
                val closed = AtomicBoolean()
                closedFlags += closed
                val released = CountDownLatch(1)
                val entered = AtomicBoolean()
                object : HttpURLConnection(incoming) {
                    override fun getResponseCode(): Int {
                        if (!sequential && entered.compareAndSet(false, true)) {
                            allRangeRequests.countDown()
                            check(allRangeRequests.await(2, TimeUnit.SECONDS))
                        }
                        return if (sequential || getRequestProperty("Range") == "bytes=0-7") 200 else 206
                    }
                    override fun getContentType() = "application/octet-stream"
                    override fun getContentEncoding(): String? = null
                    override fun getContentLengthLong() = if (sequential || getRequestProperty("Range") == "bytes=0-7") data.size.toLong() else 8L
                    override fun getHeaderField(name: String?): String? = if (name == "Content-Range")
                        "bytes ${getRequestProperty("Range").substringAfter('=')}/${data.size}" else null
                    override fun getInputStream(): InputStream = if (sequential) ByteArrayInputStream(data)
                        else object : InputStream() {
                            override fun read(): Int { released.await(); throw IOException("disconnected") }
                            override fun read(bytes: ByteArray, offset: Int, count: Int): Int = read()
                        }
                    override fun disconnect() { closed.set(true); released.countDown() }
                    override fun connect() {}
                    override fun usingProxy() = false
                }.also(connections::add)
            })
        withTimeout(4_000) { download.download(listOf(url("rangeUnsupported"))) {} }
        assertTrue(siblingsClosedBeforeFallback.get())
        assertEquals(5, connections.size)
        assertTrue(closedFlags.all { it.get() })
        assertArrayEquals(data, file.readBytes())
    }

    private suspend fun expectFailure(action: suspend () -> Unit) {
        try { action(); fail("Expected a failed download") } catch (_: IOException) {}
    }

    private class BlockingConnection(url: URL, private val began: CountDownLatch, private val total: Int) : HttpURLConnection(url) {
        val closed = AtomicBoolean()
        val responseReads = AtomicInteger()
        private val released = CountDownLatch(1)
        override fun getResponseCode(): Int { responseReads.incrementAndGet(); return 206 }
        override fun getContentLengthLong() = 8L
        override fun getContentType() = "application/octet-stream"
        override fun getContentEncoding(): String? = null
        override fun getHeaderField(name: String?): String? = if (name == "Content-Range")
            "bytes ${getRequestProperty("Range").substringAfter('=')}/$total" else null
        override fun getInputStream(): InputStream {
            began.countDown()
            return object : InputStream() {
                override fun read(): Int { released.await(); throw IOException("disconnected") }
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int = read()
            }
        }
        override fun disconnect() { closed.set(true); released.countDown() }
        override fun connect() {}
        override fun usingProxy() = false
    }

    private data class Request(val path: String, val range: String?, val method: String)
    private class TestServer(private val handler: (HttpExchange) -> Unit) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@TestServer.executor
            createContext("/") { exchange ->
                requests += Request(exchange.requestURI.path, exchange.requestHeaders.getFirst("Range"), exchange.requestMethod)
                try { handler(exchange) } finally { exchange.close() }
            }
            start()
        }
        fun open(url: URL): HttpURLConnection = URL("http://127.0.0.1:${server.address.port}${url.path}").openConnection() as HttpURLConnection
        override fun close() { server.stop(0); executor.shutdownNow() }
    }

    companion object {
        private fun send(exchange: HttpExchange, status: Int, bytes: ByteArray) {
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        private fun binary(exchange: HttpExchange, bytes: ByteArray) {
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            val range = exchange.requestHeaders.getFirst("Range")
            if (range == null) { send(exchange, 200, bytes); return }
            val parts = range.substringAfter('=').split('-')
            val start = parts[0].toInt()
            val end = parts.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex
            if (start >= bytes.size) { exchange.sendResponseHeaders(416, -1); return }
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
            send(exchange, 206, bytes.copyOfRange(start, end + 1))
        }
    }
}
