package com.zerolab.checkin.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.2：打卡方式互斥矩阵回归（创建页置灰与校验都依赖它） */
class MethodTest {

    private val manualOthers = listOf(
        Method.PHOTO.key, Method.TEXT.key, Method.LOCATION.key, Method.STEPS.key,
        Method.TIMER.key, Method.QRCODE.key, Method.NFC.key, Method.VOICE.key, Method.MOOD.key
    )

    @Test
    fun normal_blocks_all_other_manual_methods() {
        val blocked = Method.conflictsWith(setOf(Method.NORMAL.key))
        manualOthers.forEach { assertTrue("should block $it", it in blocked) }
        assertFalse(Method.NORMAL.key in blocked)
    }

    @Test
    fun other_manual_methods_block_normal() {
        val blocked = Method.conflictsWith(setOf(Method.PHOTO.key))
        assertTrue(Method.NORMAL.key in blocked)
        assertTrue(Method.AUTO.key in blocked)
        // 其他手动手法之间可以组合
        assertFalse(Method.TEXT.key in blocked)
        assertFalse(Method.VOICE.key in blocked)
    }

    @Test
    fun auto_blocks_every_manual_method() {
        val blocked = Method.conflictsWith(setOf(Method.AUTO.key))
        manualOthers.forEach { assertTrue("should block $it", it in blocked) }
        assertFalse(Method.AUTO.key in blocked)
    }

    @Test
    fun manual_method_blocks_auto() {
        val blocked = Method.conflictsWith(setOf(Method.TIMER.key))
        assertTrue(Method.AUTO.key in blocked)
    }

    @Test
    fun isValid_rejects_conflicting_combinations() {
        assertFalse(Method.isValid(listOf(Method.NORMAL.key, Method.PHOTO.key)))
        assertFalse(Method.isValid(listOf(Method.AUTO.key, Method.TEXT.key)))
        assertTrue(Method.isValid(listOf(Method.PHOTO.key, Method.TEXT.key)))
        assertTrue(Method.isValid(listOf(Method.NORMAL.key)))
    }

    @Test
    fun of_resolves_known_keys_only() {
        assertEquals(Method.TIMER, Method.of("TIMER"))
        assertEquals(Method.AUTO, Method.of(Method.AUTO.key))
        assertNull(Method.of("NOT_A_METHOD"))
        assertNull(Method.of(null))
    }

    @Test
    fun labels_drop_checkin_suffix_in_ui_but_enum_keeps_full_label() {
        assertEquals("时间打卡", Method.TIMER.label)
        assertEquals("⏳", Method.TIMER.emoji)
    }
}
