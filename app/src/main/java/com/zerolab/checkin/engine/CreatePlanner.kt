package com.zerolab.checkin.engine

import com.zerolab.checkin.util.DateUtils

/**
 * v1.4.2 重构：创建 / 编辑打卡项的「保存计划」。
 *
 * 把原先散落在 CreateItemActivity.save() 里的归一化（日记 / 习惯打卡 / 组合次数）与
 * 全部保存前校验抽成纯逻辑，不依赖任何 Android 视图，便于单元测试。
 *
 * 约定：
 *  - [plan] 会就地归一化传入的 [CreateInput.cfg]（与旧实现一致）；
 *  - 校验失败返回 [CreatePlan.Rejected]，成功返回 [CreatePlan.Ready] 里的 [CreateDraft]，
 *    由页面补上 id / sortOrder / 时间戳后落库。
 */
data class CreateInput(
    val name: String,
    val theme: String,
    val icon: String,
    val cfg: ItemConfig,
    val journalMode: Boolean,
    val moodMode: Boolean,
    val ndaysMode: Boolean,
    val ndaysTarget: Int,
    val ndaysMethods: Set<String>,
    val scheduleMode: String,
    val weekDays: Set<Int>,
    val bigSmallStart: String,
    /** 页面当前选择的策略：LOCKED / INTERVAL_N（日记项会被强制 FLEX） */
    val editPolicyRequested: String,
    val editIntervalRequested: Int?,
    /** 编辑的旧项本身是 FLEX（v1.3.6 迁移解锁项）时，保存后继续保持 FLEX */
    val editingFlexPolicy: Boolean
)

data class CreateDraft(
    val name: String,
    val type: String,
    val configJson: String,
    val icon: String,
    val theme: String,
    val editPolicy: String,
    val editInterval: Int?
)

sealed class CreatePlan {
    data class Rejected(val message: String) : CreatePlan()
    data class Ready(val draft: CreateDraft) : CreatePlan()
}

object CreatePlanner {

    /** 随心记内置的四种记录方式（v1.4.0 起固定） */
    private val JOURNAL_METHODS = listOf(
        Method.PHOTO.key, Method.TEXT.key, Method.VOICE.key, Method.LOCATION.key
    )

    fun plan(input: CreateInput): CreatePlan {
        val cfg = input.cfg
        cfg.journalMode = input.journalMode
        cfg.moodMode = input.moodMode
        // 排期以页面当前状态为准（input 为权威，避免依赖调用方是否已同步到 cfg）
        cfg.scheduleMode = input.scheduleMode
        cfg.weekDays.clear()
        cfg.weekDays.addAll(input.weekDays)
        cfg.bigSmallStart = input.bigSmallStart

        if (input.name.isBlank()) return CreatePlan.Rejected("请填写打卡名称")

        // 习惯打卡：目标天数 + 每日排期 + 不与负打卡 / 抵消共存
        if (input.ndaysMode) {
            cfg.ndaysMode = true
            cfg.ndaysTarget = input.ndaysTarget
            cfg.scheduleMode = "DAILY"
            cfg.negative = false
            cfg.offset.enabled = false
        }

        // 每周固定几天至少要选一天（日记不设排期，不参与）
        if (!input.journalMode && input.scheduleMode == "WEEKDAYS" && input.weekDays.isEmpty()) {
            return CreatePlan.Rejected("「每周固定几天」至少选择一天")
        }

        // 习惯打卡的方式以创建页 N 天区为准
        if (input.ndaysMode) {
            cfg.methods.clear()
            if (input.ndaysMethods.isEmpty()) cfg.methods.add(Method.NORMAL.key)
            else cfg.methods.addAll(input.ndaysMethods)
        }

        // 心情日记：只保留 MOOD，并隐含日记语义
        if (input.moodMode) {
            cfg.methods.clear()
            cfg.methods.add(Method.MOOD.key)
            cfg.journalMode = true
        }

        // 随心记四种方式内置（在空校验之前填充，避免被拦）
        if (cfg.journalMode && !cfg.moodMode && cfg.methods.isEmpty()) {
            cfg.methods.addAll(JOURNAL_METHODS)
        }

        if (cfg.methods.isEmpty()) return CreatePlan.Rejected("请至少打开一种打卡方式")
        if (!Method.isValid(cfg.methods)) return CreatePlan.Rejected("所选方式存在互斥冲突")

        // 随心记强制规则（兜底，防脏配置）
        if (cfg.journalMode) {
            cfg.negative = false
            cfg.timeWindowEnabled = false
            cfg.offset.enabled = false
            cfg.offset.autoConsume = false
            cfg.dailyLimit = -1
            // v1.4.2 修复：心情日记的方法必须保持 [MOOD]，不能被随心记的四种内置方式覆盖
            // （此前新建心情日记会被存成「拍照+文字+语音+位置」，打卡时弹出组合方式选择而不是心情选择）
            if (!cfg.moodMode) {
                cfg.methods.clear()
                cfg.methods.addAll(JOURNAL_METHODS)
            }
        }

        // 组合打卡每日次数固定为 1（日记 / 心情日记固定「不限」）
        if (!cfg.journalMode && cfg.methods.count { it != Method.AUTO.key } > 1) cfg.dailyLimit = 1

        // 各方式前置条件
        if (Method.LOCATION.key in cfg.methods && !cfg.journalMode && cfg.locPoints.isEmpty()) {
            return CreatePlan.Rejected("位置打卡需先获取一个标准位置")
        }
        if (Method.PHOTO.key in cfg.methods && !cfg.photoFromCamera && !cfg.photoFromAlbum) {
            return CreatePlan.Rejected("拍照打卡需至少勾选一种图片来源")
        }
        if (Method.NFC.key in cfg.methods && cfg.nfcTagId.isBlank()) {
            return CreatePlan.Rejected("NFC打卡需先绑定 NFC 标签")
        }
        if (Method.QRCODE.key in cfg.methods && cfg.qrContent.isBlank()) {
            return CreatePlan.Rejected("扫码打卡需先生成专属二维码")
        }
        if (cfg.timeWindowEnabled) {
            val a = DateUtils.parseHHmm(cfg.twStart)
            val b = DateUtils.parseHHmm(cfg.twEnd)
            if (a < 0 || b < 0 || a >= b) {
                return CreatePlan.Rejected("时间段开始需早于结束（如 05:00 / 08:30），暂不支持跨天")
            }
        }

        // 修改策略：日记固定 FLEX；迁移解锁项保持 FLEX；否则按页面选择
        val isDiary = cfg.journalMode || cfg.moodMode
        val policy = when {
            isDiary -> "FLEX"
            input.editingFlexPolicy -> "FLEX"
            input.editPolicyRequested == "INTERVAL_N" -> "INTERVAL_N"
            else -> "LOCKED"
        }
        val interval = if (policy == "INTERVAL_N") (input.editIntervalRequested ?: 7) else null

        return CreatePlan.Ready(
            CreateDraft(
                name = input.name,
                type = ItemConfig.mainType(cfg.methods),
                configJson = cfg.toJson(),
                icon = input.icon,
                theme = input.theme,
                editPolicy = policy,
                editInterval = interval
            )
        )
    }
}
