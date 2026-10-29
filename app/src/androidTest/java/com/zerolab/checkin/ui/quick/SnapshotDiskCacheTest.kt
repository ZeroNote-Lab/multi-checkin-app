package com.zerolab.checkin.ui.quick

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.ui.detail.ItemCheckinActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ObjectInputStream

/** 验证打卡页快照能写入磁盘并反序列化（杀进程后秒开依赖它） */
@RunWith(AndroidJUnit4::class)
class SnapshotDiskCacheTest {

    @Test
    fun snapshotIsWrittenAndReadable() {
        val app = ApplicationProvider.getApplicationContext<CheckinApp>()
        val repo = app.repository
        val id = repo.insertItem(
            CheckinItem(
                name = "快照缓存测试",
                type = "NORMAL",
                configJson = ItemConfig().toJson()
            )
        )
        try {
            val intent = Intent(app, ItemCheckinActivity::class.java)
                .putExtra(ItemCheckinActivity.EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<ItemCheckinActivity>(intent).use {
                Thread.sleep(1500)
            }
            val dir = File(app.cacheDir, "checkin_snapshots")
            val file = dir.listFiles()?.firstOrNull { it.name.startsWith("${id}_") }
            assertNotNull("应写入打卡页快照文件", file)
            assertTrue("快照文件不应为空", file!!.length() > 0)
            val obj = ObjectInputStream(file.inputStream().buffered()).use { it.readObject() }
            assertNotNull("快照应能反序列化", obj)
            assertTrue("反序列化类型应为 PageSnapshot", obj.javaClass.name.contains("PageSnapshot"))
        } finally {
            repo.deleteItem(id)
        }
    }
}
