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
    }

    companion object {
        lateinit var instance: CheckinApp
            private set
    }
}
