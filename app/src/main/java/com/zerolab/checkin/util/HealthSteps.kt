package com.zerolab.checkin.util

import android.content.Context
import android.os.Build
import kotlinx.coroutines.runBlocking

/**
 * 系统健康数据（Health Connect）读取「今日 0 点起」步数。
 *
 * Android 自带的 TYPE_STEP_COUNTER 只给「开机以来累计步数」，拿不到 0 点起的今日步数；
 * Health Connect 里保存了系统的步数记录，可以按当天时间范围聚合，是最准确的做法。
 *
 * 设备/系统不支持（多数未安装 Health Connect 的国产 ROM）或未授权时返回 null，
 * 由调用方回退到后台计步或实时传感器。
 */
object HealthSteps {

    /** 设备系统是否可能支持（API 26+；真正的可用性还要看 Health Connect 是否安装） */
    val supportedByOs: Boolean get() = Build.VERSION.SDK_INT >= 26

    fun isAvailable(ctx: Context): Boolean =
        supportedByOs && runCatching { Api26.isAvailable(ctx) }.getOrDefault(false)

    fun hasPermission(ctx: Context): Boolean =
        supportedByOs && runCatching { Api26.hasPermission(ctx) }.getOrDefault(false)

    /** 读取今日 0 点起步数；不可用 / 未授权 / 出错返回 null。只能在子线程调用（内部会阻塞）。 */
    fun readTodaySteps(ctx: Context): Long? =
        if (!supportedByOs) null else runCatching { Api26.readTodaySteps(ctx) }.getOrNull()

    /** Health Connect 的授权契约；系统不支持时返回 null */
    fun permissionContract(): androidx.activity.result.contract.ActivityResultContract<Set<String>, Set<String>>? =
        if (supportedByOs) runCatching { Api26.permissionContract }.getOrNull() else null

    /** 需要申请的读步数权限 */
    fun readPermission(): String? =
        if (supportedByOs) runCatching { Api26.readPermission }.getOrNull() else null

    /** 把 Health Connect 归到独立类：API 24/25 不加载它，避免低版本类校验问题 */
    private object Api26 {
        val readPermission: String =
            androidx.health.connect.client.permission.HealthPermission
                .getReadPermission(androidx.health.connect.client.records.StepsRecord::class)

        val permissionContract =
            androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()

        fun isAvailable(ctx: Context): Boolean =
            androidx.health.connect.client.HealthConnectClient.getSdkStatus(ctx) ==
                    androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE

        fun hasPermission(ctx: Context): Boolean = runBlocking {
            val client = androidx.health.connect.client.HealthConnectClient.getOrCreate(ctx)
            client.permissionController.getGrantedPermissions().contains(readPermission)
        }

        fun readTodaySteps(ctx: Context): Long? = runBlocking {
            val client = androidx.health.connect.client.HealthConnectClient.getOrCreate(ctx)
            if (!client.permissionController.getGrantedPermissions().contains(readPermission)) {
                return@runBlocking null
            }
            val zone = java.time.ZoneId.systemDefault()
            val start = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val end = java.time.Instant.now()
            val request = androidx.health.connect.client.request.AggregateRequest(
                setOf(androidx.health.connect.client.records.StepsRecord.COUNT_TOTAL),
                androidx.health.connect.client.time.TimeRangeFilter.between(start, end)
            )
            client.aggregate(request)[
                androidx.health.connect.client.records.StepsRecord.COUNT_TOTAL
            ]
        }
    }
}
