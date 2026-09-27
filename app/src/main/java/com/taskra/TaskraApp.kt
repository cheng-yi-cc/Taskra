package com.taskra

import android.app.Application
import androidx.room.Room
import com.taskra.data.AppDatabase
import com.taskra.data.BackupManager
import com.taskra.data.ImageStore
import com.taskra.data.MIGRATION_1_2
import com.taskra.data.PrefsRepository
import com.taskra.data.TaskraRepository
import com.taskra.util.SystemClock

class TaskraApp : Application() {
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

    val clock = SystemClock

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "taskra.db")
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
        repo = TaskraRepository(db)
        prefs = PrefsRepository(this)
        images = ImageStore(this, db.imageDao())
        backup = BackupManager(this, repo, prefs)
    }
}
