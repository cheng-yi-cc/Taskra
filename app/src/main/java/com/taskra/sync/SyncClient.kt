package com.taskra.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

@Serializable
internal data class PairedDevice(@SerialName("device_id") val deviceId: String, val token: String)

@Serializable
internal data class PairCode(val code: String, @SerialName("expires_in") val expiresIn: Int)

@Serializable
internal data class OutgoingChange(
    val kind: String,
    val key: String,
    @SerialName("modified_at") val modifiedAt: Long,
    val sequence: Long,
    val value: JsonElement?,
    @SerialName("clock_reliable") val clockReliable: Boolean,
    val attachments: List<String>,
    @SerialName("if_absent") val ifAbsent: Boolean = false,
)

@Serializable
private data class ChangeRequest(val records: List<OutgoingChange>)

@Serializable
internal data class RemoteRecord(
    val kind: String,
    val key: String,
    @SerialName("modified_at") val modifiedAt: Long,
    @SerialName("device_id") val deviceId: String,
    val sequence: Long,
    val value: JsonElement?,
    @SerialName("clock_reliable") val clockReliable: Boolean,
)

@Serializable
internal data class RemoteChange(val cursor: Long, val head: RemoteRecord)

@Serializable
internal data class SyncPage(val changes: List<RemoteChange>, @SerialName("has_more") val hasMore: Boolean? = null)

@Serializable
private data class CursorResponse(val cursor: Long)

@Serializable
private data class ServerTime(@SerialName("unix_ms") val unixMs: Long, @SerialName("lan_address") val lanAddress: String? = null)

@Serializable
private data class ServerState(val cursor: Long)

@Serializable
private data class PairRequest(val code: String, @SerialName("request_id") val requestId: String)

@Serializable
private data class UploadedPart(val index: Int, val size: Int, val sha256: String)

@Serializable
private data class UploadedParts(val parts: List<UploadedPart>)

@Serializable
private data class CompleteAttachment(val size: Long)

private val SyncJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun encodeOutgoingChange(value: OutgoingChange): String = SyncJson.encodeToString(value)
internal fun decodeOutgoingChange(value: String): OutgoingChange = SyncJson.decodeFromString(value)
internal fun encodeRemoteRecord(value: RemoteRecord): String = SyncJson.encodeToString(value)
internal fun decodeRemoteRecord(value: String): RemoteRecord = SyncJson.decodeFromString(value)

internal class SyncRequestException(val status: Int) : Exception("同步服务请求失败（HTTP $status）")

internal interface SyncTransport {
    suspend fun serverTime(): Long
    suspend fun push(records: List<OutgoingChange>): Long
    suspend fun pull(after: Long): List<RemoteChange>
    suspend fun pullPage(after: Long): SyncPage = SyncPage(pull(after))
    suspend fun upload(digest: String, file: File)
    suspend fun download(digest: String, destination: File)
}

/** 只允许 HTTPS，禁止自动跟随重定向，以免向其他主机泄露设备令牌。 */
internal class SyncClient(
    endpoint: String,
    private val token: String? = null,
    private val lanProxyAddress: String? = null,
    private val onLanAddress: (String?) -> Unit = {},
    private val onUploadProgress: (Long, Long) -> Unit = { _, _ -> },
) : SyncTransport {
    private val base = URI(endpoint.trimEnd('/')).also {
        require(it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.query == null && it.fragment == null) {
            "同步服务器必须使用 HTTPS"
        }
    }.toASCIIString()

    private fun connect(path: String, method: String, authenticated: Boolean): HttpURLConnection {
        val url = URI(base + path).toURL()
        val connection = if (lanProxyAddress != null) {
            require(privateLanIpv4(lanProxyAddress) != null && url.host == LAN_TLS_HOST && url.port == LAN_SYNC_PORT)
            url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress(lanProxyAddress, LAN_SYNC_PORT))) as HttpURLConnection
        } else url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = if (lanProxyAddress != null) 2_500 else 15_000
        connection.readTimeout = if (lanProxyAddress != null) 10_000 else 30_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        if (authenticated) {
            require(!token.isNullOrBlank()) { "设备尚未配对" }
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
        return connection
    }

    private fun readLimited(connection: HttpURLConnection, maximum: Int = 8 * 1024 * 1024): ByteArray {
        val status = connection.responseCode
        if (status !in 200..299) throw SyncRequestException(status)
        return connection.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= maximum) { "同步响应过大" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    private fun jsonRequest(path: String, method: String, body: ByteArray? = null, auth: Boolean = true): String {
        val connection = connect(path, method, auth)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            return readLimited(connection).toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun redeem(code: String, requestId: String = UUID.randomUUID().toString()): PairedDevice = withContext(Dispatchers.IO) {
        // 断线后重用同一个请求标识；服务器返回同一设备凭据，避免一次性配对码被提前消耗。
        require(Regex("[A-Za-z0-9_-]{16,128}").matches(requestId))
        val body = SyncJson.encodeToString(PairRequest(code, requestId)).toByteArray()
        var last: IOException? = null
        repeat(3) { attempt ->
            try {
                return@withContext SyncJson.decodeFromString<PairedDevice>(jsonRequest("/v1/pair/redeem", "POST", body, auth = false))
            } catch (e: IOException) {
                last = e
                if (attempt < 2) delay(1000L * (attempt + 1))
            }
        }
        throw requireNotNull(last)
    }

    suspend fun issuePairCode(): PairCode = withContext(Dispatchers.IO) {
        SyncJson.decodeFromString(jsonRequest("/v1/pair", "POST", "{}".toByteArray()))
    }

    suspend fun hasRemoteData(): Boolean = withContext(Dispatchers.IO) {
        SyncJson.decodeFromString<ServerState>(jsonRequest("/v1/state", "GET")).cursor > 0
    }

    override suspend fun serverTime(): Long = withContext(Dispatchers.IO) {
        val value = SyncJson.decodeFromString<ServerTime>(jsonRequest("/v1/time", "GET"))
        onLanAddress(value.lanAddress)
        value.unixMs
    }

    /** 返回的是服务器当时的全局游标，不能据此跳过其他设备尚未拉取的变更。 */
    override suspend fun push(records: List<OutgoingChange>): Long = withContext(Dispatchers.IO) {
        val body = SyncJson.encodeToString(ChangeRequest(records)).toByteArray(Charsets.UTF_8)
        require(body.size <= 5 * 1024 * 1024) { "待同步的数据过大" }
        SyncJson.decodeFromString<CursorResponse>(jsonRequest("/v1/changes", "POST", body)).cursor
    }

    override suspend fun pull(after: Long): List<RemoteChange> = pullPage(after).changes

    override suspend fun pullPage(after: Long): SyncPage = withContext(Dispatchers.IO) {
        require(after >= 0)
        SyncJson.decodeFromString<SyncPage>(jsonRequest("/v1/changes?after=$after", "GET"))
    }

    override suspend fun upload(digest: String, file: File): Unit = withContext(Dispatchers.IO) {
        require(Regex("[a-f0-9]{64}").matches(digest) && file.isFile && file.length() in 1..500L * 1024 * 1024)
        require(syncFileSha256(file) == digest) { "附件校验失败" }
        val check = connect("/v1/blobs/$digest", "HEAD", true)
        try {
            when (val status = check.responseCode) {
                200 -> return@withContext
                404 -> Unit
                else -> throw SyncRequestException(status)
            }
        } finally {
            check.disconnect()
        }
        val previous = SyncJson.decodeFromString<UploadedParts>(jsonRequest("/v1/blobs/$digest/parts", "GET")).parts
        require(previous.size <= 8000 && previous.map { it.index }.distinct().size == previous.size)
        require(previous.all { it.index in 0 until 8000 && it.size in 1..65536 && Regex("[a-f0-9]{64}").matches(it.sha256) })
        val known = previous.associateBy { it.index }
        val chunkSize = 65536
        val buffer = ByteArray(chunkSize)
        val pending = mutableListOf<Pair<Int, ByteArray>>()
        val uploaded = AtomicLong(0)
        val totalBytes = file.length()
        fun report(count: Int) { onUploadProgress(uploaded.addAndGet(count.toLong()), totalBytes) }
        onUploadProgress(0, totalBytes)
        suspend fun sendPending() = coroutineScope {
            val batch = pending.toList()
            pending.clear()
            batch.map { (partIndex, bytes) -> async(Dispatchers.IO) {
                val connection = connect("/v1/blobs/$digest/parts/$partIndex", "PUT", true)
                try {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/octet-stream")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                    readLimited(connection, 8192)
                    report(bytes.size)
                } finally { connection.disconnect() }
            } }.awaitAll()
            Unit
        }
        file.inputStream().buffered().use { input ->
            var index = 0
            while (true) {
                var count = 0
                while (count < chunkSize) {
                    val n = input.read(buffer, count, chunkSize - count)
                    if (n < 0) break
                    count += n
                }
                if (count == 0) break
                val hash = MessageDigest.getInstance("SHA-256").digest(buffer.copyOf(count)).joinToString("") { "%02x".format(it) }
                val old = known[index]
                if (old == null || old.size != count || old.sha256 != hash) {
                    pending += index to buffer.copyOf(count)
                    if (pending.size == 4) sendPending()
                } else report(count)
                index++
            }
        }
        if (pending.isNotEmpty()) sendPending()
        jsonRequest("/v1/blobs/$digest/complete", "POST", SyncJson.encodeToString(CompleteAttachment(file.length())).toByteArray())
    }

    override suspend fun download(digest: String, destination: File): Unit = withContext(Dispatchers.IO) {
        require(Regex("[a-f0-9]{64}").matches(digest))
        val parent = requireNotNull(destination.parentFile)
        require(parent.isDirectory || parent.mkdirs())
        val staged = File(parent, ".$digest.download")
        fun preserveBadPart() {
            if (staged.exists()) check(staged.renameTo(File(parent, ".$digest.invalid-${UUID.randomUUID()}"))) { "无法保留异常暂存附件" }
        }
        if (staged.exists() && (!staged.isFile || staged.length() > 500L * 1024 * 1024)) preserveBadPart()
        for (attempt in 0..1) {
            val offset = if (staged.isFile) staged.length() else 0L
            val connection = connect("/v1/blobs/$digest", "GET", true)
            try {
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val status = connection.responseCode
                if (status == 416 && offset > 0) {
                    if (syncFileSha256(staged) == digest) {
                        AtomicSyncFile.publish(staged, destination, digest)
                        return@withContext
                    }
                    preserveBadPart()
                    continue
                }
                if (status != 200 && status != 206) throw SyncRequestException(status)
                val append = status == 206
                var expectedSize: Long? = null
                if (append) {
                    val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(connection.getHeaderField("Content-Range") ?: "")
                        ?: error("附件断点响应无效")
                    val (start, end, size) = range.destructured
                    require(start.toLong() == offset && end.toLong() + 1 == size.toLong() && size.toLong() in 1..500L * 1024 * 1024) { "附件断点响应无效" }
                    expectedSize = size.toLong()
                } else {
                    connection.getHeaderField("Content-Length")?.toLongOrNull()?.let {
                        require(it in 1..500L * 1024 * 1024) { "附件大小无效" }
                        expectedSize = it
                    }
                }
                var total = if (append) offset else 0L
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(staged, append).use { output ->
                        try {
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 500L * 1024 * 1024) { "附件过大" }
                                output.write(buffer, 0, count)
                            }
                        } finally { output.fd.sync() }
                    }
                }
                if (expectedSize != null && total != expectedSize) throw java.io.EOFException("附件传输中断")
                if (total <= 0 || syncFileSha256(staged) != digest) {
                    preserveBadPart()
                    error("附件校验失败")
                }
                AtomicSyncFile.publish(staged, destination, digest)
                return@withContext
            } finally { connection.disconnect() }
        }
        error("附件断点恢复失败")
    }
}
