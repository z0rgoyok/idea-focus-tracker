package com.focustracker.services

import com.focustracker.state.FocusTimeState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.beans.PropertyChangeListener
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

@Service(Service.Level.APP)
class FocusTrackingService : Disposable {

    private val log = Logger.getInstance(FocusTrackingService::class.java)
    private val listeners = mutableListOf<() -> Unit>()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "FocusTracker-Scheduler").apply { isDaemon = true }
    }
    private var periodicTask: ScheduledFuture<*>? = null
    private var focusCheckTask: ScheduledFuture<*>? = null

    private val focusPropertyListener = PropertyChangeListener { evt ->
        if (evt.propertyName == "focusedWindow") {
            handleFocusChange(evt.newValue as? Window)
        }
    }

    @Volatile
    private var isIdeaFocused = false

    @Volatile
    private var isStarted = false

    private fun getProjectId(project: Project): String {
        val locationHash = project.locationHash
        return if (locationHash.isNotBlank()) {
            "loc:$locationHash"
        } else {
            "name:${project.name}"
        }
    }

    fun startTracking() {
        if (isStarted) return
        isStarted = true

        log.info("Starting focus tracking service")

        // Add global focus listener
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addPropertyChangeListener("focusedWindow", focusPropertyListener)

        // Check current focus state
        SwingUtilities.invokeLater {
            val focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
            handleFocusChange(focusedWindow)
        }

        // Periodic save every 5 seconds
        periodicTask = scheduler.scheduleAtFixedRate({
            try {
                saveFocusTime()
                checkDateChange()
                notifyListeners()
            } catch (e: Exception) {
                log.error("Error in periodic focus save", e)
            }
        }, 5, 5, TimeUnit.SECONDS)

        // More frequent UI update every 1 second
        focusCheckTask = scheduler.scheduleAtFixedRate({
            try {
                notifyListeners()
            } catch (e: Exception) {
                log.error("Error in UI update", e)
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun handleFocusChange(window: Window?) {
        val projectFrames = WindowManager.getInstance().allProjectFrames

        val matchedProject = resolveProjectForWindow(window, projectFrames)

        // Also check for dialogs and other IDEA windows
        val isIdea = matchedProject != null || window?.javaClass?.name?.let { className ->
            className.contains("intellij", ignoreCase = true) ||
                className.contains("idea", ignoreCase = true) ||
                className.contains("jetbrains", ignoreCase = true)
        } == true

        if (isIdea && !isIdeaFocused) {
            onFocusGained(matchedProject)
        } else if (isIdea && isIdeaFocused && matchedProject != null) {
            // Project changed while focused
            onProjectChanged(matchedProject)
        } else if (!isIdea && isIdeaFocused) {
            onFocusLost()
        }
    }

    private fun resolveProjectForWindow(window: Window?, projectFrames: Array<out IdeFrame>): Project? {
        if (window == null) return null

        val frameWindows = projectFrames.mapNotNull { frame ->
            val project = frame.project ?: return@mapNotNull null
            val frameWindow = frame.component?.let(SwingUtilities::getWindowAncestor)
            frameWindow?.let { it to project }
        }

        // Try direct match, then follow the owner chain (dialogs/windows owned by the main IDE frame).
        var current: Window? = window
        while (current != null) {
            frameWindows.firstOrNull { (frameWindow, _) -> frameWindow == current }?.let { return it.second }
            current = current.owner
        }

        // Fallback: if there is exactly one open project, attribute the time to it.
        IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project?.let { lastProject ->
            if (!lastProject.isDisposed) return lastProject
        }

        val openProjects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        return openProjects.singleOrNull()
    }

    private fun onFocusGained(project: Project?) {
        val state = FocusTimeState.getInstance()

        // Don't start tracking if paused
        if (state.isPaused) {
            log.info("IDEA window gained focus but tracking is paused")
            isIdeaFocused = true
            notifyListeners()
            return
        }

        log.info("IDEA window gained focus, project: ${project?.name}")
        isIdeaFocused = true

        val now = System.currentTimeMillis()
        synchronized(state) {
            val todayKey = state.getTodayKey()

            if (state.sessionDate != todayKey) {
                state.sessionDate = todayKey
            }

            state.sessionStartTime = now
            state.focusSessionStartTime = now
            // Avoid dropping the last known project when we can't resolve it for a focused IDEA dialog/window.
            state.activeProject = project?.let { p ->
                val projectId = getProjectId(p)
                state.projectDisplayNames[projectId] = p.name
                projectId
            } ?: state.activeProject
        }

        notifyListeners()
    }

    private fun onProjectChanged(project: Project) {
        val state = FocusTimeState.getInstance()

        val projectId = getProjectId(project)
        synchronized(state) {
            state.projectDisplayNames[projectId] = project.name
            if (state.activeProject == projectId) return
            if (state.isPaused) {
                state.activeProject = projectId
                return
            }
        }

        log.info("Project changed from ${state.activeProject} to $projectId")

        // Save time for previous project
        saveFocusTime()

        // Start tracking new project
        synchronized(state) {
            state.sessionStartTime = System.currentTimeMillis()
            state.activeProject = projectId
        }

        notifyListeners()
    }

    private fun onFocusLost() {
        log.info("IDEA window lost focus")
        isIdeaFocused = false
        saveFocusTime()
        val state = FocusTimeState.getInstance()
        synchronized(state) {
            state.focusSessionStartTime = null
        }
        notifyListeners()
    }

    fun togglePause() {
        val state = FocusTimeState.getInstance()

        val wasPaused = synchronized(state) { state.isPaused }

        if (wasPaused) {
            // Resume tracking
            synchronized(state) {
                state.isPaused = false
                if (isIdeaFocused) {
                    val now = System.currentTimeMillis()
                    state.sessionStartTime = now
                    state.focusSessionStartTime = now
                    state.sessionDate = state.getTodayKey()
                }
            }
            log.info("Tracking resumed")
        } else {
            // Pause tracking - save current time first
            saveFocusTime()
            synchronized(state) {
                state.isPaused = true
                state.sessionStartTime = null
                state.focusSessionStartTime = null
            }
            log.info("Tracking paused")
        }

        notifyListeners()
    }

    fun isPaused(): Boolean = FocusTimeState.getInstance().isPaused

    private fun saveFocusTime() {
        val state = FocusTimeState.getInstance()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        synchronized(state) {
            val startTime = state.sessionStartTime ?: return
            if (state.isPaused) return

            val clampedStart = minOf(startTime, now)
            val projectId = state.activeProject

            var cursor = clampedStart
            var date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            var totalSaved = 0L

            while (cursor < now) {
                val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val segmentEnd = minOf(now, nextMidnight)
                val elapsed = (segmentEnd - cursor).coerceAtLeast(0L)
                val dateKey = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

                state.dailyFocusTime[dateKey] = (state.dailyFocusTime[dateKey] ?: 0L) + elapsed

                if (projectId != null) {
                    val projectData = state.projectFocusTime.getOrPut(projectId) { mutableMapOf() }
                    projectData[dateKey] = (projectData[dateKey] ?: 0L) + elapsed
                }

                totalSaved += elapsed
                cursor = segmentEnd
                date = date.plusDays(1)
            }

            state.sessionDate = LocalDate.now(zone).format(DateTimeFormatter.ISO_LOCAL_DATE)

            // Reset checkpoint start to now (continue tracking)
            if (isIdeaFocused && !state.isPaused) {
                state.sessionStartTime = now
            } else {
                state.sessionStartTime = null
            }

            log.debug("Saved focus time: ${totalSaved}ms, project: $projectId")
        }
    }

    private fun checkDateChange() {
        val state = FocusTimeState.getInstance()
        val todayKey = state.getTodayKey()

        synchronized(state) {
            if (state.sessionDate != null && state.sessionDate != todayKey) {
                log.info("Date changed from ${state.sessionDate} to $todayKey")
                state.sessionDate = todayKey

                if (isIdeaFocused && !state.isPaused) {
                    state.sessionStartTime = System.currentTimeMillis()
                }
            }
        }
    }

    fun isFocused(): Boolean = isIdeaFocused

    fun isTracking(): Boolean = isIdeaFocused && !isPaused()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach {
                try {
                    it()
                } catch (e: Exception) {
                    log.error("Error notifying listener", e)
                }
            }
        }
    }

    fun getCurrentSessionTime(): Long {
        val state = FocusTimeState.getInstance()
        if (state.isPaused || !isIdeaFocused) return 0L
        val start = synchronized(state) { state.focusSessionStartTime }
        return start?.let {
            System.currentTimeMillis() - it
        } ?: 0L
    }

    fun getActiveProjectId(): String? {
        val state = FocusTimeState.getInstance()
        return synchronized(state) { state.activeProject }
    }

    fun getOpenProjectsInfo(): List<FocusTimeState.ProjectInfo> {
        val openProjects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        return openProjects.map { p ->
            val projectId = getProjectId(p)
            val state = FocusTimeState.getInstance()
            synchronized(state) {
                state.projectDisplayNames[projectId] = p.name
            }
            FocusTimeState.ProjectInfo(id = projectId, name = p.name)
        }
    }

    fun getActiveProjectDisplayName(): String? {
        val state = FocusTimeState.getInstance()
        val projectId = synchronized(state) { state.activeProject } ?: return null
        return state.getProjectDisplayName(projectId)
    }

    fun getActiveProjectName(): String? = getActiveProjectDisplayName()

    override fun dispose() {
        log.info("Disposing focus tracking service")

        // Save final state
        saveFocusTime()

        // Remove listeners
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .removePropertyChangeListener("focusedWindow", focusPropertyListener)

        // Shutdown scheduler
        periodicTask?.cancel(false)
        focusCheckTask?.cancel(false)
        scheduler.shutdown()
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        listeners.clear()
    }

    companion object {
        fun getInstance(): FocusTrackingService =
            ApplicationManager.getApplication().getService(FocusTrackingService::class.java)
    }
}
