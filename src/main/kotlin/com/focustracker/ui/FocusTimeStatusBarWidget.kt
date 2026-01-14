package com.focustracker.ui

import com.focustracker.services.FocusTrackingService
import com.focustracker.state.FocusTimeState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.Icon

class FocusTimeStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "FocusTimeWidget"

    override fun getDisplayName(): String = "Focus Time"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = FocusTimeStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class FocusTimeStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private val updateListener: () -> Unit = { statusBar?.updateWidget(ID()) }

    init {
        FocusTrackingService.getInstance().addListener(updateListener)
    }

    override fun ID(): String = "FocusTimeWidget"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        FocusTrackingService.getInstance().removeListener(updateListener)
        statusBar = null
    }

    override fun getText(): String {
        val state = FocusTimeState.getInstance()
        val service = FocusTrackingService.getInstance()
        val todayTime = state.getTodayFocusTime()

        val icon = when {
            service.isPaused() -> "\u23F8" // Paused
            service.isFocused() -> "\u23F1" // Timer running
            else -> "\u23F9" // Stopped
        }
        return "$icon ${formatDuration(todayTime)}"
    }

    override fun getTooltipText(): String {
        val state = FocusTimeState.getInstance()
        val service = FocusTrackingService.getInstance()

        val status = when {
            service.isPaused() -> "Paused"
            service.isFocused() -> "Tracking"
            else -> "Not in focus"
        }
        val today = formatDuration(state.getTodayFocusTime())
        val session = formatDuration(service.getCurrentSessionTime())
        val project = service.getActiveProjectDisplayName() ?: "None"

        return """
            Focus Tracker - $status
            Project: $project
            Today: $today
            Session: $session

            Click to open dashboard
        """.trimIndent()
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ToolWindowManager.getInstance(project).getToolWindow("Focus Tracker")?.show()
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%02d:%02d", minutes, secs)
        }
    }
}
