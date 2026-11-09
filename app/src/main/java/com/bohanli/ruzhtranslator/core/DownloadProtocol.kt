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
}
