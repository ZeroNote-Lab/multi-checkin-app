package com.zerolab.checkin

import android.app.Application
import com.zerolab.checkin.data.AppDatabase
import com.zerolab.checkin.data.repo.CheckinRepository

class CheckinApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: CheckinRepository by lazy { CheckinRepository(database) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // v1.4.2：补齐旧停用项的停用区间（停用期间不再记缺卡）。
        // 同步执行，保证首屏状态就是迁移后的结果；只处理停用项，代价很小。
        try { repository.migrateLegacyPauseRanges() } catch (_: Exception) {}
    }

    companion object {
        lateinit var instance: CheckinApp
            private set
    }
}
