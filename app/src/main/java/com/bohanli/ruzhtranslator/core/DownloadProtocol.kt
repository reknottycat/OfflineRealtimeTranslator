package com.bohanli.ruzhtranslator.core

import java.net.URI

/** Pure protocol checks, unit-testable without an Android device. */
object DownloadProtocol {
    fun requireHttps(url: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "模型下载必须使用不含登录凭据的 HTTPS 文件链接"
        }
    }
    fun rangeTotal(header: String?, offset: Long, responseLength: Long): Long {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(header ?: "")
            ?: throw IllegalArgumentException("Invalid Content-Range")
        val (start, end, total) = match.destructured
        val a = start.toLong(); val b = end.toLong(); val n = total.toLong()
        require(a == offset && b >= a && n > b) { "Incorrect resume byte range" }
        require(responseLength < 0 || responseLength == b - a + 1) { "Range length mismatch" }
        return n
    }

    /** Every parallel response must be exactly the requested interval of this artifact. */
    fun requireRange(header: String?, start: Long, end: Long, total: Long, responseLength: Long) {
        val actual = rangeTotal(header, start, responseLength)
        require(actual == total) { "下载源返回了其他文件的总长度" }
        val actualEnd = header!!.substringAfter('-').substringBefore('/').toLong()
        require(actualEnd == end) { "下载源返回了错误的分块范围" }
        require(responseLength < 0 || responseLength == end - start + 1) { "下载源返回了错误的分块长度" }
    }

    fun requireBinary(contentType: String?, encoding: String?) {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        require(!type.startsWith("text/") && type != "application/json" && type != "application/problem+json") {
            "下载源返回了网页或错误文本，不是模型文件"
        }
        require(encoding.isNullOrBlank() || encoding.equals("identity", ignoreCase = true)) {
            "下载源对模型进行了不兼容的内容压缩"
        }
    }
}
