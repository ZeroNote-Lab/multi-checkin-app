package com.zerolab.checkin.theme

/**
 * v1.3.18 打卡项图标体系
 *
 * 图标独立于配色主题：CheckinItem.icon 存稳定 key（如 "water"），
 * 渲染时经 IconManager 映射为 emoji；旧数据 icon="default"（或空）时
 * 回退到该打卡项 theme 的主题 emoji，保证老数据图标不变化。
 */
data class ItemIcon(
    val key: String,
    val emoji: String,
    val name: String
)

object IconManager {
    /** 21 个图标：生活常用在前（首屏 7 个），花卉与其余生活类在「更多」中 */
    val all = listOf(
        // 常用 7（首屏直接展示）
        ItemIcon("water", "💧", "喝水"),
        ItemIcon("run", "🏃", "跑步"),
        ItemIcon("book", "📚", "阅读"),
        ItemIcon("bed", "🛏️", "早睡"),
        ItemIcon("alarm", "⏰", "早起"),
        ItemIcon("gym", "💪", "健身"),
        ItemIcon("coffee", "☕", "咖啡"),
        // 更多 14
        ItemIcon("nodevice", "📵", "戒手机"),
        ItemIcon("bike", "🚲", "骑行"),
        ItemIcon("meditate", "🧘", "冥想"),
        ItemIcon("paint", "🎨", "绘画"),
        ItemIcon("music", "🎵", "音乐"),
        ItemIcon("apple", "🍎", "饮食"),
        ItemIcon("pill", "💊", "吃药"),
        ItemIcon("sakura", "🌸", "樱花"),
        ItemIcon("sunflower", "🌻", "向日葵"),
        ItemIcon("tulip", "🌷", "郁金香"),
        ItemIcon("rose", "🌹", "玫瑰"),
        ItemIcon("clover", "🍀", "四叶草"),
        ItemIcon("cactus", "🌵", "仙人掌"),
        ItemIcon("maple", "🍁", "枫叶")
    )

    /** 首屏常用（方案B：7 个图标 + 1 个「更多」格） */
    val common = all.take(7)

    private val map = all.associateBy { it.key }

    fun of(key: String?): ItemIcon = map[key] ?: all.first()

    fun isKnown(key: String?): Boolean = key != null && key in map

    /** 渲染入口：icon 有效用 icon，否则回退主题 emoji（老数据） */
    fun emojiFor(icon: String?, theme: String?): String {
        val k = icon ?: "default"
        return if (k == "default" || k.isBlank()) ThemeManager.of(theme).emoji else of(k).emoji
    }
}
