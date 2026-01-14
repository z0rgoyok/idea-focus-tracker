package com.focustracker.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class FocusTrackerStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Start tracking when first project opens
        FocusTrackingService.getInstance().startTracking()
    }
}
