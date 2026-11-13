package com.bohanli.ruzhtranslator.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Bounded, resumable HTTP transport shared by Android and the JVM transport tests.
 * A journal digest detects torn/changed local chunks, not authenticity: only the final
 * catalog SHA-256 authenticates a model. This class never installs or replaces a model.
 */
internal class ModelDownloadTransport(
    private val spec: ModelCatalog.Spec,
    private val partial: File,
    private val chunkBytes: Int = 4 * 1024 * 1024,
    private val workers: Int = 4,
    private val attemptsPerChunk: Int = 8,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 20_000,
    private val handshakeTimeoutMs: Long = 45_000,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    data class Progress(val received: Long, val expected: Long, val stage: String)
    private val journal = File(partial.parentFile, "${spec.fileName}.chunks.done")
    private val journalTemp = File(partial.parentFile, "${spec.fileName}.chunks.done.tmp")
    private val chunkCount get() = ((spec.bytes + chunkBytes - 1) / chunkBytes).toInt()
    private val checkpointLock = Any()

    init {
        require(spec.bytes > 0 && spec.bytes <= spec.maximumBytes) { "分块下载需要已核实的精确模型大小" }
        require(chunkBytes in 1..(16 * 1024 * 1024))
        require(workers in 1..8 && attemptsPerChunk in 1..8)
        require(connectTimeoutMs > 0 && readTimeoutMs > 0 && handshakeTimeoutMs > 0)
    }

    suspend fun download(urls: List<String>, progress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val sources = urls.distinct()
        require(sources.isNotEmpty()) { "没有可用的模型下载源" }
        sources.forEach(DownloadProtocol::requireHttps)
        val directory = partial.parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { "无法创建模型目录" }
        val sink = ProgressSink(progress)

        // A legacy sequential download, or cancellation after full SHA verification.
        if (!journal.exists() && partial.isFile && partial.length() == spec.bytes) {
            if (verify(partial) { done -> sink.emit(done, "校验已有文件 SHA-256") }) return@withContext
            resetPartial()
        }
        val completed = restoreJournal(sink)
        val completedBytes = completed.keys.sumOf { lengthOf(it).toLong() }
        sink.setCompleted(completedBytes)
        check(directory.usableSpace >= spec.bytes - completedBytes + 32L * 1024 * 1024) { "剩余存储空间不足" }
        // Sparse reservation is safe: only journaled and rehashed intervals count as downloaded.
        RandomAccessFile(partial, "rw").use { it.setLength(spec.bytes) }
        writeJournal(completed)

        try {
            downloadChunks(sources, completed, sink)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: RangeUnavailable) {
            sink.emit(completed.keys.sumOf { lengthOf(it).toLong() }, "下载源不支持分块，切换顺序下载")
            // All workers have joined before touching the shared partial file.
            prepareSequential(completed)
            downloadSequential(sources, sink)
            return@withContext
        }

        if (verify(partial) { done -> sink.emit(done, "校验完整模型 SHA-256") }) return@withContext
        // Never retain poisoned chunks across a whole-file digest failure. A clean,
        // single-source retry also repairs a bad prefix from an old app version.
        sink.emit(0, "完整 SHA-256 不匹配，清除损坏分块并逐源重新下载")
        resetPartial()
        downloadSequential(sources, sink, cleanEachSource = true)
    }

    private suspend fun downloadChunks(sources: List<String>, completed: MutableMap<Int, String>, sink: ProgressSink) = coroutineScope {
        val missing = (0 until chunkCount).filterNot(completed::containsKey)
        val cursor = AtomicInteger()
        val pool = SourcePool(sources)
        List(minOf(workers, missing.size)) {
            async(Dispatchers.IO) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val position = cursor.getAndIncrement()
                    if (position >= missing.size) break
                    val index = missing[position]
                    val bytes = fetchChunk(index, pool, sink)
                    currentCoroutineContext().ensureActive()
                    // Separate descriptors plus non-overlapping offsets avoid shared seek races.
                    RandomAccessFile(partial, "rw").use { file ->
                        file.seek(startOf(index))
                        file.write(bytes)
                        file.fd.sync()
                    }
                    currentCoroutineContext().ensureActive()
                    synchronized(checkpointLock) {
                        completed[index] = digest(bytes)
                        try { writeJournal(completed) }
                        catch (failure: Exception) { completed.remove(index); throw failure }
                        sink.commit(index, bytes.size.toLong(), "分块已保存 · ${completed.size}/$chunkCount")
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun fetchChunk(index: Int, pool: SourcePool, sink: ProgressSink): ByteArray {
        var last: Exception? = null
        for (attempt in 0 until attemptsPerChunk) {
            currentCoroutineContext().ensureActive()
            val source = pool.next(attempt) ?: if (pool.allLackRanges()) throw RangeUnavailable()
                else throw IOException("所有下载源都拒绝了当前模型：${last?.message.orEmpty()}", last)
            val label = "${URL(source).host} · 源 ${pool.indexOf(source) + 1}/${pool.size} · 分块 ${index + 1}/$chunkCount · 尝试 ${attempt + 1}/$attemptsPerChunk"
            sink.active(index, 0, "连接 $label")
            try {
                val start = startOf(index)
                val length = lengthOf(index)
                val end = start + length - 1
                val bytes = response(source, "bytes=$start-$end") { connection, active ->
                    val status = connection.responseCode
                    if (status == 200) {
                        try {
                            DownloadProtocol.requireBinary(connection.contentType, connection.contentEncoding)
                            require(connection.contentLengthLong < 0 || connection.contentLengthLong == spec.bytes) {
                                "下载源返回了其他文件的大小"
                            }
                        } catch (bad: IllegalArgumentException) {
                            throw RejectedSource(bad.message ?: "响应不是所需模型")
                        }
                        throw NoRange()
                    }
                    if (status != 206) throw HttpFailure(status)
                    try {
                        DownloadProtocol.requireBinary(connection.contentType, connection.contentEncoding)
                        DownloadProtocol.requireRange(connection.getHeaderField("Content-Range"), start, end,
                            spec.bytes, connection.contentLengthLong)
                    } catch (bad: IllegalArgumentException) { throw RejectedSource(bad.message ?: "分块响应不符合模型记录") }
                    val data = ByteArray(length)
                    connection.inputStream.use { input ->
                        var count = 0
                        while (count < length) {
                            active()
                            val read = input.read(data, count, minOf(256 * 1024, length - count))
                            if (read < 0) throw IOException("分块响应提前结束")
                            if (read == 0) continue
                            count += read
                            sink.active(index, count.toLong(), "下载 $label")
                        }
                        active()
                        if (input.read() != -1) throw RejectedSource("分块响应超出了请求长度")
                    }
                    data
                }
                pool.succeeded(source)
                return bytes
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                last = failure
                pool.failed(source, failure)
                sink.clear(index, "${URL(source).host}：${failureText(failure)}；自动切换备用源")
                if (pool.allLackRanges()) throw RangeUnavailable()
                if (attempt + 1 < attemptsPerChunk) delay(minOf(250L * (attempt + 1), 1_000L))
            }
        }
        if (pool.hasSequentialCandidate()) throw RangeUnavailable()
        throw IOException("分块 ${index + 1} 已尝试 $attemptsPerChunk 次；已保存可恢复的分块。${failureText(last)}", last)
    }

    /** A 200 response to Range is never copied into a parallel interval. */
    private suspend fun downloadSequential(sources: List<String>, sink: ProgressSink, cleanEachSource: Boolean = false) {
        val failures = mutableListOf<String>()
        for ((position, source) in sources.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (cleanEachSource) resetPartial()
            var retryCleanPrefix = true
            while (true) {
                val beganWithPrefix = partial.length() > 0
                val label = "${URL(source).host} · 源 ${position + 1}/${sources.size} · 顺序下载"
                sink.emit(partial.length(), "连接 $label")
                try {
                    sequentialOne(source, sink, label)
                    if (verify(partial) { done -> sink.emit(done, "校验完整模型 SHA-256 · ${URL(source).host}") }) return
                    resetPartial()
                    sink.emit(0, "${URL(source).host}：SHA-256 不匹配，已清除损坏断点")
                    if (beganWithPrefix && retryCleanPrefix) { retryCleanPrefix = false; continue }
                    failures += "${URL(source).host}：SHA-256 不匹配"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    failures += "${URL(source).host}：${failureText(failure)}"
                    sink.emit(partial.length(), "${failures.last()}；自动切换备用源")
                }
                break
            }
        }
        throw IOException("所有下载源均未完成，已保留可恢复断点：${failures.distinct().joinToString("；")}")
    }

    private suspend fun sequentialOne(source: String, sink: ProgressSink, label: String) {
        if (partial.length() > spec.bytes) resetPartial()
        var retry416 = true
        while (true) {
            val offset = partial.length()
            try {
                response(source, if (offset > 0) "bytes=$offset-" else null) { connection, active ->
                    val status = connection.responseCode
                    if (status == 416) throw HttpFailure(416)
                    if (status != 200 && status != 206) throw HttpFailure(status)
                    DownloadProtocol.requireBinary(connection.contentType, connection.contentEncoding)
                    val resumed = status == 206
                    val length = connection.contentLengthLong
                    if (resumed) DownloadProtocol.requireRange(connection.getHeaderField("Content-Range"),
                        offset, spec.bytes - 1, spec.bytes, length)
                    else require(length < 0 || length == spec.bytes) { "下载源返回了其他文件的大小" }
                    var received = if (resumed) offset else 0L
                    connection.inputStream.use { input ->
                        FileOutputStream(partial, resumed).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                active()
                                val read = input.read(buffer)
                                if (read < 0) break
                                require(received + read <= spec.bytes) { "下载内容超出模型大小" }
                                output.write(buffer, 0, read)
                                received += read
                                sink.emit(received, label)
                            }
                            output.fd.sync()
                        }
                    }
                    require(received == spec.bytes) { "下载中断，已保留断点" }
                }
                return
            } catch (range: HttpFailure) {
                if (range.status != 416 || !retry416) throw range
                retry416 = false
                resetPartial()
            }
        }
    }

    /**
     * Blocking HTTP is cancellable through both thread interruption and disconnect.
     * Each request owns its watcher, so a failed worker cannot leave live sibling writes
     * behind when coroutineScope joins. Handshake budget covers all redirect hops.
     */
    private suspend fun <T> response(source: String, range: String?, block: (HttpURLConnection, () -> Unit) -> T): T = coroutineScope {
        val connection = AtomicReference<HttpURLConnection?>()
        val handshakeDone = AtomicBoolean()
        val timedOut = AtomicBoolean()
        val context = currentCoroutineContext()
        val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                delay(handshakeTimeoutMs)
                if (!handshakeDone.get()) {
                    timedOut.set(true)
                    connection.get()?.disconnect()
                }
                awaitCancellation()
            } finally { connection.getAndSet(null)?.disconnect() }
        }
        try {
            runInterruptible(Dispatchers.IO) {
                var url = URL(source)
                var result: T? = null
                var found = false
                for (hop in 0 until 6) {
                    context.ensureActive()
                    if (timedOut.get()) throw SocketTimeoutException("下载源握手超时")
                    DownloadProtocol.requireHttps(url.toString())
                    val current = openConnection(url)
                    connection.set(current)
                    context.ensureActive()
                    if (timedOut.get()) throw SocketTimeoutException("下载源握手超时")
                    current.connectTimeout = connectTimeoutMs
                    current.readTimeout = readTimeoutMs
                    current.instanceFollowRedirects = false
                    current.setRequestProperty("Accept-Encoding", "identity")
                    current.setRequestProperty("User-Agent", "OfflineRealtimeTranslator/3.6")
                    if (range != null) current.setRequestProperty("Range", range)
                    val status = current.responseCode
                    if (timedOut.get()) throw SocketTimeoutException("下载源握手超时")
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val location = current.getHeaderField("Location") ?: throw IOException("重定向缺少地址")
                        url = URL(url, location)
                        connection.getAndSet(null)?.disconnect()
                        continue
                    }
                    handshakeDone.set(true)
                    result = block(current) { context.ensureActive() }
                    found = true
                    break
                }
                if (!found) throw IOException("下载源重定向次数过多")
                @Suppress("UNCHECKED_CAST")
                result as T
            }
        } catch (failure: Exception) {
            context.ensureActive()
            if (timedOut.get()) throw SocketTimeoutException("下载源在 ${handshakeTimeoutMs / 1000} 秒内未建立文件连接")
            throw failure
        } finally {
            connection.getAndSet(null)?.disconnect()
            watcher.cancel()
        }
    }

    private suspend fun restoreJournal(sink: ProgressSink): MutableMap<Int, String> {
        val result = mutableMapOf<Int, String>()
        if (!partial.exists()) { journal.delete(); journalTemp.delete(); return result }
        if (journal.exists()) {
            val lines = runCatching { journal.readLines() }.getOrElse { emptyList() }
            if (lines.firstOrNull() != journalHeader()) { resetPartial(); return result }
            RandomAccessFile(partial, "r").use { file ->
                for (line in lines.drop(1)) {
                    currentCoroutineContext().ensureActive()
                    val parts = line.split(':')
                    val index = parts.firstOrNull()?.toIntOrNull() ?: continue
                    if (parts.size != 2 || index !in 0 until chunkCount || !Regex("[a-f0-9]{64}").matches(parts[1])) continue
                    if (partial.length() < startOf(index) + lengthOf(index)) continue
                    if (hashInterval(file, startOf(index), lengthOf(index)) == parts[1]) result[index] = parts[1]
                    sink.emit(result.keys.sumOf { lengthOf(it).toLong() }, "检查已保存分块 · ${result.size}/$chunkCount")
                }
            }
        } else {
            // Import only complete legacy intervals; incomplete final chunks are fetched again.
            if (partial.length() > spec.bytes) { resetPartial(); return result }
            RandomAccessFile(partial, "r").use { file ->
                for (index in 0 until chunkCount) {
                    currentCoroutineContext().ensureActive()
                    if (partial.length() < startOf(index) + lengthOf(index)) break
                    result[index] = hashInterval(file, startOf(index), lengthOf(index))
                }
            }
        }
        return result
    }

    private fun hashInterval(file: RandomAccessFile, offset: Long, length: Int): String {
        file.seek(offset)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(minOf(64 * 1024, length))
        var remaining = length
        while (remaining > 0) {
            val read = file.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) throw IOException("本地分块不完整")
            digest.update(buffer, 0, read)
            remaining -= read
        }
        return hex(digest.digest())
    }

    private fun writeJournal(completed: Map<Int, String>) {
        val body = buildString {
            append(journalHeader()).append('\n')
            completed.toSortedMap().forEach { (index, hash) -> append(index).append(':').append(hash).append('\n') }
        }
        FileOutputStream(journalTemp).use { file -> file.write(body.toByteArray(Charsets.UTF_8)); file.fd.sync() }
        Files.move(journalTemp.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun prepareSequential(completed: Map<Int, String>) {
        var prefix = 0L
        for (index in 0 until chunkCount) {
            if (index !in completed) break
            prefix += lengthOf(index)
        }
        RandomAccessFile(partial, "rw").use { it.setLength(prefix); it.fd.sync() }
        check(!journal.exists() || journal.delete()) { "无法重置分块记录" }
        journalTemp.delete()
    }

    private fun resetPartial() {
        for (file in listOf(journal, journalTemp, partial)) check(!file.exists() || file.delete()) { "无法清除损坏断点 ${file.name}" }
    }

    private fun journalHeader() = "ORT-CHUNKS-1 ${spec.sha256} ${spec.bytes} $chunkBytes"
    private fun startOf(index: Int) = index.toLong() * chunkBytes
    private fun lengthOf(index: Int) = minOf(chunkBytes.toLong(), spec.bytes - startOf(index)).toInt()

    private suspend fun verify(file: File, progress: (Long) -> Unit): Boolean {
        if (!file.isFile || file.length() != spec.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(512 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                count += read
                if (count % (64L * 1024 * 1024) < buffer.size) progress(count)
            }
        }
        return hex(digest.digest()) == spec.sha256
    }

    private inner class ProgressSink(private val callback: (Progress) -> Unit) {
        private var completed = 0L
        private val active = mutableMapOf<Int, Long>()
        private var lastProgressNanos = 0L
        @Synchronized fun setCompleted(bytes: Long) { completed = bytes }
        @Synchronized fun active(index: Int, bytes: Long, stage: String) {
            active[index] = bytes
            report(stage, force = bytes == 0L)
        }
        @Synchronized fun clear(index: Int, stage: String) { active.remove(index); report(stage, force = true) }
        @Synchronized fun commit(index: Int, bytes: Long, stage: String) {
            active.remove(index); completed += bytes; report(stage, force = completed == spec.bytes)
        }
        @Synchronized fun emit(bytes: Long, stage: String) {
            val now = System.nanoTime()
            // Status transitions remain immediate; high-frequency byte updates are throttled.
            if (stage.contains("顺序下载") && !stage.startsWith("连接") && bytes < spec.bytes &&
                now - lastProgressNanos < 100_000_000L) return
            lastProgressNanos = now
            callback(Progress(bytes.coerceIn(0, spec.bytes), spec.bytes, stage))
        }
        private fun report(stage: String, force: Boolean) {
            val now = System.nanoTime()
            if (!force && now - lastProgressNanos < 100_000_000L) return
            lastProgressNanos = now
            callback(Progress((completed + active.values.sum()).coerceAtMost(spec.bytes), spec.bytes, stage))
        }
    }

    private class SourcePool(private val sources: List<String>) {
        private val rejected = ConcurrentHashMap.newKeySet<String>()
        private val noRange = ConcurrentHashMap.newKeySet<String>()
        private val cooldown = ConcurrentHashMap<String, Long>()
        val size get() = sources.size
        fun indexOf(source: String) = sources.indexOf(source)
        fun allLackRanges() = sources.all { it in noRange || it in rejected } && noRange.isNotEmpty()
        fun hasSequentialCandidate() = noRange.isNotEmpty()
        fun next(attempt: Int): String? {
            val live = sources.filter { it !in rejected && it !in noRange }
            if (live.isEmpty()) return null
            val now = System.nanoTime()
            val ready = live.filter { (cooldown[it] ?: 0L) <= now }
            val candidates = if (ready.isNotEmpty()) ready else live
            return (sources.drop(attempt % sources.size) + sources.take(attempt % sources.size)).firstOrNull { it in candidates }
        }
        fun succeeded(source: String) { cooldown.remove(source) }
        fun failed(source: String, failure: Exception) {
            when {
                failure is NoRange -> noRange.add(source)
                failure is RejectedSource -> rejected.add(source)
                failure is HttpFailure && failure.status in listOf(400, 401, 403, 404, 410, 416) -> rejected.add(source)
                else -> cooldown[source] = System.nanoTime() + 30_000_000_000L
            }
        }
    }

    private class NoRange : IOException("下载源不支持 Range 分块")
    private class RangeUnavailable : IOException("没有支持分块的下载源")
    private class RejectedSource(message: String) : IOException(message)
    private class HttpFailure(val status: Int) : IOException("HTTP $status")
    private fun failureText(failure: Exception?): String = when (failure) {
        is UnknownHostException -> "域名解析失败"
        is SocketTimeoutException -> "连接或读取超时"
        is SSLException -> "TLS 连接失败"
        is HttpFailure, is RejectedSource, is NoRange, is IllegalArgumentException -> failure.message.orEmpty()
        else -> "连接中断或响应不完整"
    }
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
