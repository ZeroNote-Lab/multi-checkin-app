package com.zerolab.checkin.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.data.entity.CheckinRecord
import com.zerolab.checkin.data.repo.CheckinRepository
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.engine.PauseRange
import com.zerolab.checkin.util.DateUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** v1.4.2：停用区间的落库 / 兼容迁移（内存 Room） */
@RunWith(AndroidJUnit4::class)
class PauseRangeStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CheckinRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = CheckinRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun cfgOf(id: Long): ItemConfig =
        ItemConfig.parse(repo.getItem(id)!!.configJson)

    @Test
    fun pause_startsOpenRange() {
        val id = repo.insertItem(CheckinItem(name = "t", type = "NORMAL", configJson = ItemConfig().toJson()))
        repo.setActive(id, false)
        val c = cfgOf(id)
        assertEquals(1, c.pausedRanges.size)
        assertEquals(DateUtils.today(), c.pausedRanges[0].from)
        assertEquals("", c.pausedRanges[0].to)
    }

    @Test
    fun resume_closesRangeAtYesterday() {
        val from = DateUtils.addDays(DateUtils.today(), -3)
        val cfg = ItemConfig().apply { pausedRanges.add(PauseRange(from, "")) }
        val id = repo.insertItem(
            CheckinItem(name = "t", type = "NORMAL", configJson = cfg.toJson(), isActive = 0)
        )
        repo.setActive(id, true)
        val c = cfgOf(id)
        assertEquals(1, c.pausedRanges.size)
        assertEquals(from, c.pausedRanges[0].from)
        assertEquals(DateUtils.addDays(DateUtils.today(), -1), c.pausedRanges[0].to)
    }

    @Test
    fun migrateLegacyPausedItem_startsAfterLastRecord() {
        val id = repo.insertItem(
            CheckinItem(name = "old", type = "NORMAL", configJson = ItemConfig().toJson(), isActive = 0)
        )
        val last = DateUtils.addDays(DateUtils.today(), -3)
        repo.insertRecord(
            CheckinRecord(
                itemId = id, checkinDate = last,
                checkinTime = System.currentTimeMillis(), status = "SUCCESS"
            )
        )
        repo.migrateLegacyPauseRanges()
        val c = cfgOf(id)
        assertEquals(1, c.pausedRanges.size)
        assertEquals(DateUtils.addDays(last, 1), c.pausedRanges[0].from)
        assertTrue(c.pausedRanges[0].to.isBlank())
    }
}
