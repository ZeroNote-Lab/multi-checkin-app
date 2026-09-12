package com.zerolab.checkin.engine

/**
 * 打卡方式开关枚举。v6：每种方式都是一个独立布尔开关，可多选（即组合）。
 */
enum class Method(val key: String, val label: String, val emoji: String) {
    NORMAL("NORMAL", "普通打卡", "✅"),
    PHOTO("PHOTO", "拍照打卡", "📷"),
    TEXT("TEXT", "文字打卡", "📝"),
    LOCATION("LOCATION", "位置打卡", "📍"),
    STEPS("STEPS", "步数打卡", "👟"),
    TIMER("TIMER", "倒计时打卡", "⏳"),
    QRCODE("QRCODE", "扫码打卡", "🔳"),
    NFC("NFC", "NFC打卡", "📡"),
    VOICE("VOICE", "语音打卡", "🎤"),
    AUTO("AUTO", "自动打卡", "⚡");

    companion object {
        fun of(key: String?): Method? = values().firstOrNull { it.key == key }

        /**
         * 互斥矩阵：返回与 [selected] 集合互斥、因此必须置灰的方式。
         * 依据 PRD 11.1：
         *  - AUTO 与 NORMAL/PHOTO/TEXT/VOICE/TIMER/QRCODE/NFC 互斥
         *  - NORMAL 与 TIMER 互斥
         */
        fun conflictsWith(selected: Set<String>): Set<String> {
            val blocked = mutableSetOf<String>()
            // 普通打卡与其他所有手动方式互斥（用户要求：选了普通，其他都不可选）
            val others = listOf(PHOTO.key, TEXT.key, LOCATION.key, STEPS.key, TIMER.key, QRCODE.key, NFC.key, VOICE.key)
            if (NORMAL.key in selected) blocked += others
            if (others.any { it in selected }) blocked += NORMAL.key
            // 自动打卡与需人工操作/素材的方式互斥
            if (AUTO.key in selected) {
                blocked += listOf(NORMAL.key, PHOTO.key, TEXT.key, VOICE.key, TIMER.key, QRCODE.key, NFC.key)
            }
            if (listOf(NORMAL.key, PHOTO.key, TEXT.key, VOICE.key, TIMER.key, QRCODE.key, NFC.key).any { it in selected }) {
                blocked += AUTO.key
            }
            // 自身不算互斥
            return blocked - selected
        }

        /** 校验一组方式是否合法（无互斥） */
        fun isValid(set: Collection<String>): Boolean {
            return conflictsWith(set.toSet()).none { it in set }
        }
    }
}
