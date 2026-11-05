package com.taskra

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskra.data.*
import com.taskra.sync.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private class FakeSyncServer {
    private val heads = mutableMapOf<Pair<String, String>, RemoteRecord>()
    private val events = mutableListOf<Pair<String, String>>()
    private val seen = mutableMapOf<List<Any>, OutgoingChange>()
    val blobs = mutableMapOf<String, ByteArray>()
    val downloads = ConcurrentHashMap<String, Int>()
    var failAfterAcceptFor: String? = null
    val eventCount get() = events.size

    fun transport(device: String) = object : SyncTransport {
        override suspend fun serverTime() = System.currentTimeMillis()
        override suspend fun upload(digest: String, file: File) {
            require(syncFileSha256(file) == digest)
            blobs[digest] = file.readBytes()
        }
        override suspend fun download(digest: String, destination: File) {
            downloads.merge(device, 1, Int::plus)
            destination.parentFile!!.mkdirs()
            destination.writeBytes(requireNotNull(blobs[digest]))
        }
        override suspend fun push(records: List<OutgoingChange>): Long {
            records.forEach { record ->
                require(record.attachments.all { it in blobs })
                val versionKey = listOf(record.kind, record.key, record.modifiedAt, device, record.sequence)
                val old = seen[versionKey]
                if (old != null) {
                    check(old == record)
                } else {
                    if (record.ifAbsent && heads.containsKey(record.kind to record.key)) throw IOException("已有记录冲突")
                    seen[versionKey] = record
                    val key = record.kind to record.key
                    val value = record.value?.let { document -> buildJsonObject {
                        put("payload", document)
                        put("attachments", JsonArray(record.attachments.map(::JsonPrimitive)))
                    } }
                    val incoming = RemoteRecord(record.kind, record.key, record.modifiedAt, device, record.sequence, value, record.clockReliable)
                    val current = heads[key]
                    if (current == null || !current.clockReliable || !incoming.clockReliable ||
                        SyncVersion(incoming.modifiedAt, incoming.deviceId, incoming.sequence) > SyncVersion(current.modifiedAt, current.deviceId, current.sequence)) {
                        heads[key] = incoming
                    }
                    events += key
                }
            }
            if (failAfterAcceptFor == device) { failAfterAcceptFor = null; throw IOException("模拟确认丢失") }
            return events.size.toLong()
        }
        override suspend fun pull(after: Long): List<RemoteChange> = events.drop(after.toInt()).mapIndexed { index, key ->
            RemoteChange(after + index + 1, heads.getValue(key))
        }
    }
}

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    @Test fun receivedTextSurvivesSubsequentNetworkFailure() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val root = File(app.cacheDir, "sync-page-retry-${UUID.randomUUID()}").apply { mkdirs() }
        fun context(name: String) = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(root, name).apply { mkdirs() }
        }
        val phone = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val tablet = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val ps = phone.openHelper.writableDatabase
            val ts = tablet.openHelper.writableDatabase
            SyncJournalSchema.install(ps); SyncJournalSchema.install(ts)
            phone.withTransaction { SyncJournalSchema.bindDevice(ps, "phone") }
            tablet.withTransaction { SyncJournalSchema.bindDevice(ts, "tablet") }
            phone.semesterDao().upsert(Semester("s", "学期"))
            phone.homeworkDao().upsert(Homework("h", "s", null, "已收到的作业"))
            phone.withTransaction { SyncJournalSchema.bootstrap(ps) }
            tablet.withTransaction { SyncJournalSchema.bootstrap(ts) }
            val server = FakeSyncServer()
            val assess: (Long) -> AssessedTime = { AssessedTime(it, true) }
            assertFalse(SyncEngine(context("phone"), phone, "phone", server.transport("phone")).runOnce(assess).pending)
            val delegate = server.transport("tablet")
            val flaky = object : SyncTransport by delegate {
                override suspend fun pullPage(after: Long): SyncPage {
                    if (after != 0L) throw IOException("模拟分页网络中断")
                    return SyncPage(delegate.pull(after), hasMore = true)
                }
            }
            val result = SyncEngine(context("tablet"), tablet, "tablet", flaky).runOnce(assess)
            assertTrue(result.pending)
            assertEquals("已收到的作业", tablet.homeworkDao().get("h")?.title)
            assertFalse(SyncEngine(context("tablet"), tablet, "tablet", delegate).runOnce(assess).pending)
        } finally {
            phone.close(); tablet.close(); root.deleteRecursively()
        }
    }

    @Test fun textAppearsWhileAttachmentsDownloadInParallel() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val root = File(app.cacheDir, "sync-parallel-${UUID.randomUUID()}").apply { mkdirs() }
        fun context(name: String) = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(root, name).apply { mkdirs() }
        }
        val phoneContext = context("phone")
        val tabletContext = context("tablet")
        val phone = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val tablet = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val phoneSql = phone.openHelper.writableDatabase
            val tabletSql = tablet.openHelper.writableDatabase
            SyncJournalSchema.install(phoneSql); SyncJournalSchema.install(tabletSql)
            phone.withTransaction { SyncJournalSchema.bindDevice(phoneSql, "phone") }
            tablet.withTransaction { SyncJournalSchema.bindDevice(tabletSql, "tablet") }
            phone.semesterDao().upsert(Semester("s", "学期"))
            phone.homeworkDao().upsert(Homework("text", "s", null, "先显示的文字"))
            repeat(4) { index ->
                val id = "pic$index"
                val image = File(phoneContext.filesDir, "images/$id.jpg").apply { parentFile!!.mkdirs() }
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.rgb(index * 50, 20, 30))
                image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                bitmap.recycle()
                phone.imageDao().upsert(ImageAsset(id, "images/$id.jpg", 8, 8, image.length()))
            }
            phone.withTransaction { SyncJournalSchema.bootstrap(phoneSql) }
            tablet.withTransaction { SyncJournalSchema.bootstrap(tabletSql) }
            val server = FakeSyncServer()
            val assess: (Long) -> AssessedTime = { AssessedTime(it, true) }
            assertFalse(SyncEngine(phoneContext, phone, "phone", server.transport("phone")).runOnce(assess).pending)
            val delegate = server.transport("tablet")
            val concurrent = AtomicInteger(0)
            val twoStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val transport = object : SyncTransport by delegate {
                override suspend fun download(digest: String, destination: File) {
                    if (concurrent.incrementAndGet() >= 2) twoStarted.complete(Unit)
                    try { release.await(); delegate.download(digest, destination) }
                    finally { concurrent.decrementAndGet() }
                }
            }
            val run = async { SyncEngine(tabletContext, tablet, "tablet", transport).runOnce(assess) }
            try {
                withTimeout(10_000) { twoStarted.await() }
                assertEquals("先显示的文字", tablet.homeworkDao().get("text")?.title)
            } finally { release.complete(Unit) }
            assertFalse(run.await().pending)
            assertEquals(4, tablet.imageDao().listAll().size)
        } finally {
            phone.close(); tablet.close(); root.deleteRecursively()
        }
    }

    @Test fun verifiedIncomingCacheAvoidsDownloadingExistingAttachment() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val root = File(app.cacheDir, "sync-preloaded-${UUID.randomUUID()}").apply { mkdirs() }
        fun context(name: String) = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(root, name).apply { mkdirs() }
        }
        val phoneContext = context("phone")
        val tabletContext = context("tablet")
        val phone = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val tablet = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val phoneSql = phone.openHelper.writableDatabase
            val tabletSql = tablet.openHelper.writableDatabase
            SyncJournalSchema.install(phoneSql)
            SyncJournalSchema.install(tabletSql)
            phone.withTransaction { SyncJournalSchema.bindDevice(phoneSql, "phone") }
            tablet.withTransaction { SyncJournalSchema.bindDevice(tabletSql, "tablet") }
            val image = File(phoneContext.filesDir, "images/preloaded.jpg").apply { parentFile!!.mkdirs() }
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            bitmap.recycle()
            phone.semesterDao().upsert(Semester("s", "学期"))
            phone.imageDao().upsert(ImageAsset("preloaded", "images/preloaded.jpg", 8, 8, image.length()))
            phone.homeworkDao().upsert(Homework("h", "s", null, "含图片作业"))
            phone.blockDao().upsertAll(listOf(ContentBlock("b", "h", kind = BlockKind.IMAGE, imageId = "preloaded")))
            phone.withTransaction { SyncJournalSchema.bootstrap(phoneSql) }
            tablet.withTransaction { SyncJournalSchema.bootstrap(tabletSql) }
            val server = FakeSyncServer()
            val assess: (Long) -> AssessedTime = { AssessedTime(it, true) }
            assertFalse(SyncEngine(phoneContext, phone, "phone", server.transport("phone")).runOnce(assess).pending)
            PendingBlobStore(File(tabletContext.filesDir, "sync-incoming/blobs")).stage(image)
            assertFalse(SyncEngine(tabletContext, tablet, "tablet", server.transport("tablet")).runOnce(assess).pending)
            assertEquals(0, server.downloads.getOrDefault("tablet", 0))
            assertEquals(syncFileSha256(image), syncFileSha256(File(tabletContext.filesDir, "images/preloaded.jpg")))
            assertEquals("含图片作业", tablet.homeworkDao().get("h")!!.title)
        } finally {
            phone.close(); tablet.close(); root.deleteRecursively()
        }
    }

    @Test fun lostAcknowledgmentRetriesSameVersionAndKeepsNewerLocalEdit() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val root = File(app.cacheDir, "sync-retry-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir(): File = File(root, "files").apply { mkdirs() } }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val sql = db.openHelper.writableDatabase
            SyncJournalSchema.install(sql)
            db.withTransaction { SyncJournalSchema.bindDevice(sql, "phone") }
            db.semesterDao().upsert(Semester("s", "学期"))
            val homework = Homework("h", "s", null, "初始")
            db.homeworkDao().upsert(homework)
            db.withTransaction { SyncJournalSchema.bootstrap(sql) }
            val server = FakeSyncServer()
            val engine = SyncEngine(context, db, "phone", server.transport("phone"))
            val assess: (Long) -> AssessedTime = { AssessedTime(it, true) }
            assertFalse(engine.runOnce(assess).pending)
            val initialEvents = server.eventCount
            db.homeworkDao().upsert(homework.copy(title = "先上传"))
            server.failAfterAcceptFor = "phone"
            assertTrue(runCatching { engine.runOnce(assess) }.exceptionOrNull() is IOException)
            db.homeworkDao().upsert(homework.copy(title = "确认丢失后的新编辑"))
            assertFalse(engine.runOnce(assess).pending)
            assertEquals(initialEvents + 2, server.eventCount)
            assertEquals("确认丢失后的新编辑", db.homeworkDao().get("h")!!.title)
            sql.query("SELECT COUNT(*) FROM sync_outbox").use { check(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            sql.query("SELECT COUNT(*) FROM sync_dirty").use { check(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        } finally {
            db.close(); root.deleteRecursively()
        }
    }

    @Test fun twoOfflineDevicesConvergeWithoutLosingIndependentWorkOrAttachments() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskraApp
        val root = File(app.cacheDir, "sync-engine-${UUID.randomUUID()}").apply { mkdirs() }
        fun context(name: String) = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(root, name).apply { mkdirs() }
        }
        val phoneContext = context("phone")
        val tabletContext = context("tablet")
        val phone = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val tablet = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val phoneSql = phone.openHelper.writableDatabase
            val tabletSql = tablet.openHelper.writableDatabase
            SyncJournalSchema.install(phoneSql)
            SyncJournalSchema.install(tabletSql)
            phone.withTransaction { SyncJournalSchema.bindDevice(phoneSql, "phone") }
            tablet.withTransaction { SyncJournalSchema.bindDevice(tabletSql, "tablet") }
            val image = File(phoneContext.filesDir, "images/img.jpg").apply { parentFile!!.mkdirs() }
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            bitmap.recycle()
            val audio = File(phoneContext.filesDir, "audio/voice.m4a").apply { parentFile!!.mkdirs(); writeText("录音数据") }
            phone.semesterDao().upsert(Semester("s", "学期"))
            phone.courseDao().upsert(Course("c", "s", "课程"))
            phone.imageDao().upsert(ImageAsset("img", "images/img.jpg", 8, 8, image.length()))
            phone.homeworkDao().upsert(Homework("shared", "s", "c", "共享作业"))
            phone.blockDao().upsertAll(listOf(
                ContentBlock("t", "shared", position = 0, text = "原始内容"),
                ContentBlock("i", "shared", kind = BlockKind.IMAGE, position = 1, imageId = "img"),
                ContentBlock("a", "shared", kind = BlockKind.AUDIO, position = 2, audioId = "voice"),
            ))
            phone.draftDao().upsert(EditorDraft("new", blocksJson = encodeBlocks(listOf(BlockJson("p", BlockKind.TEXT, "手机草稿")))))
            phone.withTransaction { SyncJournalSchema.bootstrap(phoneSql) }
            tablet.withTransaction { SyncJournalSchema.bootstrap(tabletSql) }
            val server = FakeSyncServer()
            val phoneEngine = SyncEngine(phoneContext, phone, "phone", server.transport("phone"))
            val tabletEngine = SyncEngine(tabletContext, tablet, "tablet", server.transport("tablet"))
            val assess: (Long) -> AssessedTime = { AssessedTime(it, true) }
            assertFalse(phoneEngine.runOnce(assess).pending)
            assertFalse(tabletEngine.runOnce(assess).pending)
            assertEquals(0, server.downloads.getOrDefault("phone", 0))
            assertTrue(server.downloads.getOrDefault("tablet", 0) > 0)
            assertEquals("共享作业", tablet.homeworkDao().get("shared")!!.title)
            assertEquals(syncFileSha256(image), syncFileSha256(File(tabletContext.filesDir, "images/img.jpg")))
            assertEquals(syncFileSha256(audio), syncFileSha256(File(tabletContext.filesDir, "audio/voice.m4a")))
            assertEquals("手机草稿", decodeBlocks(tablet.draftDao().get("new:phone")!!.blocksJson).single().text)

            val current = System.currentTimeMillis()
            phone.homeworkDao().upsert(phone.homeworkDao().get("shared")!!.copy(title = "手机较早修改"))
            tablet.homeworkDao().upsert(tablet.homeworkDao().get("shared")!!.copy(title = "平板较晚修改"))
            phoneSql.execSQL("UPDATE sync_dirty SET modified_at=? WHERE kind='homework' AND record_id='shared'", arrayOf(current + 1000))
            tabletSql.execSQL("UPDATE sync_dirty SET modified_at=? WHERE kind='homework' AND record_id='shared'", arrayOf(current + 2000))
            phone.homeworkDao().upsert(Homework("phone-only", "s", "c", "手机新增"))
            tablet.homeworkDao().upsert(Homework("tablet-only", "s", "c", "平板新增"))
            tablet.draftDao().upsert(EditorDraft("new", blocksJson = encodeBlocks(listOf(BlockJson("t", BlockKind.TEXT, "平板草稿")))))
            assertFalse(phoneEngine.runOnce(assess).pending)
            assertFalse(tabletEngine.runOnce(assess).pending)
            assertFalse(phoneEngine.runOnce(assess).pending)
            assertEquals("平板较晚修改", phone.homeworkDao().get("shared")!!.title)
            assertEquals("平板较晚修改", tablet.homeworkDao().get("shared")!!.title)
            assertNotNull(phone.homeworkDao().get("tablet-only"))
            assertNotNull(tablet.homeworkDao().get("phone-only"))
            assertNotNull(phone.draftDao().get("new"))
            assertNotNull(phone.draftDao().get("new:tablet"))
            assertNotNull(tablet.draftDao().get("new"))
            assertNotNull(tablet.draftDao().get("new:phone"))
        } finally {
            phone.close(); tablet.close(); root.deleteRecursively()
        }
    }
}
