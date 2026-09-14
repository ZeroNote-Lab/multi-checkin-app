package com.zerolab.checkin.ui.quick

/**
 * NFC 标签回调总线（v6.1.0）。
 * 承载打卡界面的 Activity（MainActivity / ItemCheckinActivity）在前台注册 NFC 前台分发，
 * 读到标签后统一经 [dispatch] 转发给当前激活的打卡 Fragment，避免「列表进入的打卡页收不到 NFC」。
 */
object NfcHub {

    @Volatile
    private var active: QuickCheckinFragment? = null

    /** 打卡 Fragment 可见时把自己注册为唯一接收者（后注册覆盖前者） */
    fun register(f: QuickCheckinFragment) {
        active = f
    }

    /** 打卡 Fragment 不可见时注销（仅当仍是自己时，避免误清后来的注册者） */
    fun unregister(f: QuickCheckinFragment) {
        if (active === f) active = null
    }

    /** 任意外层 Activity 读到 NFC 标签后调用 */
    fun dispatch(tagId: String) {
        active?.notifyNfc(tagId)
    }
}
