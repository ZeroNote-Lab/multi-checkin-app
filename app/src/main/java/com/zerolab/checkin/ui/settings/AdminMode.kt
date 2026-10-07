package com.zerolab.checkin.ui.settings

/**
 * v1.3.17：超级管理员模式（内存态）
 * 设置页版本号连点 5 次开启；退出 App（onDestroy / onTaskRemoved）自动关闭。
 * 开启期间：可修改任意打卡项（含 LOCKED）、可对任意打卡记录做修改/删除/素材清理。
 */
object AdminMode {
    @Volatile
    var isOn: Boolean = false
        private set

    fun on() { isOn = true }
    fun off() { isOn = false }
}
