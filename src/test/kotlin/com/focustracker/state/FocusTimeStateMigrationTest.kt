package com.focustracker.state

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusTimeStateMigrationTest {

    @Test
    fun `migrateMissingBranchData attributes remainder to unknown branch`() {
        val state = FocusTimeState().apply {
            projectFocusTime["loc:test"] = mutableMapOf(
                "2026-01-29" to 500L,
                "2026-01-30" to 1_000L
            )
            branchFocusTime["loc:test"] = mutableMapOf(
                "main" to mutableMapOf(
                    "2026-01-30" to 700L
                )
            )
        }

        state.migrateMissingBranchData()

        val unknown = state.branchFocusTime["loc:test"]?.get(FocusTimeState.UNKNOWN_BRANCH).orEmpty()
        assertEquals(500L, unknown["2026-01-29"])
        assertEquals(300L, unknown["2026-01-30"])
    }

    @Test
    fun `migrateMissingBranchData adds to existing unknown branch`() {
        val state = FocusTimeState().apply {
            projectFocusTime["loc:test"] = mutableMapOf(
                "2026-01-30" to 1_000L
            )
            branchFocusTime["loc:test"] = mutableMapOf(
                FocusTimeState.UNKNOWN_BRANCH to mutableMapOf(
                    "2026-01-30" to 100L
                ),
                "main" to mutableMapOf(
                    "2026-01-30" to 700L
                )
            )
        }

        state.migrateMissingBranchData()

        val unknown = state.branchFocusTime["loc:test"]?.get(FocusTimeState.UNKNOWN_BRANCH).orEmpty()
        assertEquals(300L, unknown["2026-01-30"])
    }

    @Test
    fun `migrateMissingBranchData does not subtract when branches exceed project totals`() {
        val state = FocusTimeState().apply {
            projectFocusTime["loc:test"] = mutableMapOf(
                "2026-01-30" to 1_000L
            )
            branchFocusTime["loc:test"] = mutableMapOf(
                "main" to mutableMapOf(
                    "2026-01-30" to 1_200L
                )
            )
        }

        state.migrateMissingBranchData()

        val unknown = state.branchFocusTime["loc:test"]?.get(FocusTimeState.UNKNOWN_BRANCH)
        assertEquals(null, unknown?.get("2026-01-30"))
    }
}
