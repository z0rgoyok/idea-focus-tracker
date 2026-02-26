package com.focustracker.state

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `projectHasBranchMatching returns true for stored branch`() {
        val state = FocusTimeState().apply {
            branchFocusTime["loc:test"] = mutableMapOf(
                "feature/379-fix" to mutableMapOf("2026-02-12" to 1_000L)
            )
        }

        assertTrue(state.projectHasBranchMatching("loc:test", "379"))
        assertFalse(state.projectHasBranchMatching("loc:test", "no-match"))
    }

    @Test
    fun `projectHasBranchMatching considers active branch even before persistence`() {
        val state = FocusTimeState().apply {
            activeProject = "loc:test"
            activeBranch = "feature/379-live"
        }

        assertTrue(state.projectHasBranchMatching("loc:test", "379"))
    }

    @Test
    fun `getBranchKeysMatching returns exact matching branch keys`() {
        val state = FocusTimeState().apply {
            branchFocusTime["loc:test"] = mutableMapOf(
                "feature/379-fix" to mutableMapOf("2026-02-12" to 1_000L),
                "feature/111" to mutableMapOf("2026-02-12" to 1_000L)
            )
        }

        assertEquals(setOf("feature/379-fix"), state.getBranchKeysMatching("loc:test", "379"))
    }

    @Test
    fun `project branch aggregates include only selected branches`() {
        val state = FocusTimeState()
        val today = LocalDate.now()
        val todayKey = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayKey = today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

        state.branchFocusTime["loc:test"] = mutableMapOf(
            "feature/111" to mutableMapOf(todayKey to 10_000L, yesterdayKey to 20_000L),
            "feature/379" to mutableMapOf(todayKey to 30_000L, yesterdayKey to 40_000L)
        )

        val selection = mapOf("loc:test" to setOf("feature/379"))

        assertEquals(30_000L, state.getTodayFocusTimeForProjectBranches(selection))
        assertEquals(70_000L, state.getTotalFocusTimeForProjectBranches(selection))

        val period = state.getPeriodFocusTimeForProjectBranches(days = 2, projectBranches = selection)
        assertEquals(30_000L, period[todayKey])
        assertEquals(40_000L, period[yesterdayKey])
    }
}
