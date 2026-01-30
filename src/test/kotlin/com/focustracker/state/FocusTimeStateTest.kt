package com.focustracker.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusTimeStateTest {

    @Test
    fun `default template project is not shown in project stats`() {
        val state = FocusTimeState()
        val today = state.getTodayKey()

        val templateId = "loc:deadbeef"
        val realId = "name:RealProject"

        state.projectDisplayNames[templateId] = "Default (Template) Project"
        state.projectFocusTime[templateId] = mutableMapOf(today to 60_000L)
        state.projectFocusTime[realId] = mutableMapOf(today to 120_000L)

        val rows = state.getAllProjectsStats(emptyList())
        assertFalse(rows.any { it.id == templateId })
        assertTrue(rows.any { it.id == realId })
    }

    @Test
    fun `default template project is not shown in ai project stats`() {
        val state = FocusTimeState()
        val today = state.getTodayKey()

        val templateId = "loc:deadbeef"
        val realId = "name:RealProject"

        state.projectDisplayNames[templateId] = "Default (Template) Project"
        state.aiProjectTime[templateId] = mutableMapOf(today to 60_000L)
        state.aiProjectTime[realId] = mutableMapOf(today to 120_000L)

        val rows = state.getAiProjectsStats(emptyList())
        assertFalse(rows.any { it.id == templateId })
        assertTrue(rows.any { it.id == realId })
    }
}
