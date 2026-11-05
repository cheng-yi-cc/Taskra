package com.taskra

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.taskra.data.AppDatabase
import com.taskra.data.BackupManager
import com.taskra.data.ImageStore
import com.taskra.data.MIGRATION_1_2
import com.taskra.data.MIGRATION_4_5
import com.taskra.data.MIGRATION_5_6
import com.taskra.data.MIGRATION_3_4
import com.taskra.data.MIGRATION_2_3
import com.taskra.data.PrefsRepository
import com.taskra.data.TaskraRepository
import com.taskra.util.SystemClock
import com.taskra.reminders.HomeworkReminders
import com.taskra.sync.SyncJournalSchema
import com.taskra.sync.SyncCoordinator

class TaskraApp : Application() {
    var openingShown = false
    lateinit var db: AppDatabase
        private set
    lateinit var repo: TaskraRepository
        private set
    lateinit var prefs: PrefsRepository
        private set
    lateinit var images: ImageStore
        private set
    lateinit var backup: BackupManager
        private set
    lateinit var reminders: HomeworkReminders
        private set
    internal lateinit var sync: SyncCoordinator
        private set

    val clock = SystemClock

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "taskra.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    SyncJournalSchema.install(db)
                }
            })
            .build()
        repo = TaskraRepository(db, filesDir)
        prefs = PrefsRepository(this)
        images = ImageStore(this, db)
        backup = BackupManager(this, repo, prefs)
        sync = SyncCoordinator(this, db)
        reminders = HomeworkReminders(this)
        reminders.start()
    }
}
