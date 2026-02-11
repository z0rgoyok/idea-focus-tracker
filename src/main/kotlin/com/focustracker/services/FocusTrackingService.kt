package com.focustracker.services

import com.focustracker.state.FocusTimeState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import java.awt.AWTEvent
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.beans.PropertyChangeListener
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.LinkedHashSet
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
    @Volatile
    private var lastHeartbeatAt: Long = 0L
    private var focusLossGraceStopTask: ScheduledFuture<*>? = null
    private var focusGainStartTask: ScheduledFuture<*>? = null

    private val focusLossGraceMillis = TimeUnit.MINUTES.toMillis(2)

    @Volatile
    private var lastUserActivityAt: Long = System.currentTimeMillis()

    @Volatile
    private var isIdlePaused: Boolean = false

    @Volatile
    private var lastIdeaWindow: Window? = null

    private val awtActivityListener = java.awt.event.AWTEventListener { event ->
        try {
            if (!isIdeaWindowFocused) return@AWTEventListener
            if (!isActivityEventFromIdeaWindow(event)) return@AWTEventListener

            if (!UserActivityEvents.isMeaningful(event)) return@AWTEventListener

            lastUserActivityAt = System.currentTimeMillis()

            if (isIdlePaused && !FocusTimeState.getInstance().isPaused) {
                resumeFromIdle()
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun isActivityEventFromIdeaWindow(event: AWTEvent): Boolean {
        val eventWindow = when (event) {
            is MouseEvent -> event.component?.let(SwingUtilities::getWindowAncestor)
                ?: (event.source as? java.awt.Component)?.let(SwingUtilities::getWindowAncestor)
                ?: (event.source as? Window)

            is MouseWheelEvent -> event.component?.let(SwingUtilities::getWindowAncestor)
                ?: (event.source as? java.awt.Component)?.let(SwingUtilities::getWindowAncestor)
                ?: (event.source as? Window)

            is KeyEvent -> event.component?.let(SwingUtilities::getWindowAncestor)
                ?: (event.source as? java.awt.Component)?.let(SwingUtilities::getWindowAncestor)
                ?: (event.source as? Window)

            else -> null
        }

        val projectFrames = WindowManager.getInstance().allProjectFrames
        val matchedProject = resolveProjectForWindow(eventWindow, projectFrames)
        val activeFrameProject = resolveActiveProjectFromFrames(projectFrames)

        return matchedProject != null ||
            activeFrameProject != null ||
            eventWindow?.javaClass?.name?.let { className ->
                className.contains("intellij", ignoreCase = true) ||
                    className.contains("idea", ignoreCase = true) ||
                    className.contains("jetbrains", ignoreCase = true)
            } == true
    }

    private val focusPropertyListener = PropertyChangeListener { evt ->
        if (evt.propertyName == "focusedWindow") {
            handleFocusChange(evt.newValue as? Window)
        }
    }

    @Volatile
    private var isIdeaFocused = false

    @Volatile
    private var isIdeaWindowFocused = false

    @Volatile
    private var isStarted = false

    @Volatile
    private var recentProjectsBackfilled = false

    @Volatile
    private var currentActiveProject: Project? = null

    private fun getProjectId(project: Project): String {
        val locationHash = project.locationHash
        return if (locationHash.isNotBlank()) {
            "loc:$locationHash"
        } else {
            "name:${project.name}"
        }
    }

    /**
     * Gets the current Git branch for the given project.
     * Returns null if Git4Idea is not available or project has no Git repo.
     */
    private fun getCurrentBranch(project: Project?): String? {
        if (project == null || project.isDisposed) return null
        return try {
            val gitService = GitBranchService.getInstanceOrNull() ?: return null
            ReadAction.compute<String?, Throwable> {
                gitService.getCurrentBranch(project)
            }
        } catch (e: Exception) {
            log.debug("Unable to get current branch", e)
            null
        }
    }

    /**
     * Updates the active branch in state if it has changed.
     * Saves time for the previous branch before switching.
     */
    private fun checkBranchChange() {
        if (!isIdeaFocused) return
        val project = currentActiveProject ?: return
        if (project.isDisposed) return

        val state = FocusTimeState.getInstance()
        if (state.isPaused) return

        val currentBranch = getCurrentBranch(project)
        val previousBranch = synchronized(state) { state.activeBranch }

        if (currentBranch != previousBranch) {
            log.info("Branch changed from $previousBranch to $currentBranch")
            // Save time for previous branch before switching
            saveFocusTime()
            synchronized(state) {
                state.activeBranch = currentBranch
                state.sessionStartTime = System.currentTimeMillis()
            }
            notifyListeners()
        }
    }

    fun startTracking() {
        if (isStarted) return
        isStarted = true

        log.info("Starting focus tracking service")

        // Session markers should never survive IDE restart/crash.
        // Clear them before periodic save starts to avoid offline-gap spikes.
        val state = FocusTimeState.getInstance()
        synchronized(state) {
            state.sessionStartTime = null
            state.focusSessionStartTime = null
        }

        // Best-effort backfill for older state entries that may not have stored paths yet.
        scheduler.execute { backfillProjectPathsFromRecentProjects() }

        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(
                awtActivityListener,
                AWTEvent.MOUSE_EVENT_MASK or
                    AWTEvent.MOUSE_MOTION_EVENT_MASK or
                    AWTEvent.MOUSE_WHEEL_EVENT_MASK or
                    AWTEvent.KEY_EVENT_MASK
            )
        } catch (e: Throwable) {
            log.debug("Unable to register AWT activity listener", e)
        }

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
                handlePossibleSystemSuspend()
                handleUserIdle()
                checkBranchChange()
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
                handlePossibleSystemSuspend()
                handleUserIdle()
                notifyListeners()
            } catch (e: Exception) {
                log.error("Error in UI update", e)
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun handlePossibleSystemSuspend() {
        val now = System.currentTimeMillis()
        val last = lastHeartbeatAt
        lastHeartbeatAt = now

        if (last == 0L) return
        if (now - last <= SYSTEM_SUSPEND_GAP_THRESHOLD_MILLIS) return

        // Scheduler was paused (e.g., machine sleep). Prevent counting the whole gap as focus time.
        val state = FocusTimeState.getInstance()
        synchronized(state) {
            if (state.isPaused) return

            // Reset idle state; we'll re-establish activity on next input.
            isIdlePaused = false

            if (isIdeaFocused) {
                state.sessionStartTime = now
                state.focusSessionStartTime = now
                state.sessionDate = state.getTodayKey()
            } else {
                state.sessionStartTime = null
                state.focusSessionStartTime = null
            }
        }
    }

    private fun handleUserIdle() {
        if (!isIdeaFocused) return
        if (!isIdeaWindowFocused) return

        val state = FocusTimeState.getInstance()
        if (state.isPaused) return

        val now = System.currentTimeMillis()
        val idleFor = now - lastUserActivityAt
        if (idleFor <= USER_IDLE_THRESHOLD_MILLIS) return

        val cutoff = lastUserActivityAt + USER_IDLE_THRESHOLD_MILLIS
        pauseForIdle(cutoff)
    }

    private fun handleFocusChange(window: Window?) {
        val projectFrames = WindowManager.getInstance().allProjectFrames

        val matchedProject = resolveProjectForWindow(window, projectFrames)
        val activeFrameProject = resolveActiveProjectFromFrames(projectFrames)

        // Also check for dialogs and other IDEA windows
        val isIdea = matchedProject != null || activeFrameProject != null || window?.javaClass?.name?.let { className ->
            className.contains("intellij", ignoreCase = true) ||
                className.contains("idea", ignoreCase = true) ||
                className.contains("jetbrains", ignoreCase = true)
        } == true

        val effectiveProject = matchedProject ?: activeFrameProject

        isIdeaWindowFocused = isIdea
        if (isIdea) {
            lastIdeaWindow = window ?: findActiveFrameWindow(projectFrames)
        }

        if (isIdea && focusLossGraceStopTask != null) {
            focusLossGraceStopTask?.cancel(false)
            focusLossGraceStopTask = null
            notifyListeners()
        }

        if (isIdea) {
            if (!isIdeaFocused && focusGainStartTask == null) {
                scheduleFocusGained(effectiveProject)
            } else if (!isIdeaFocused && focusGainStartTask != null) {
                // Still waiting for delayed start; update pending project association if possible.
                updatePendingProject(effectiveProject)
            } else if (isIdeaFocused && effectiveProject != null) {
                // Project changed while focused
                lastUserActivityAt = System.currentTimeMillis()
                onProjectChanged(effectiveProject)
            }
            return
        }

        // Not an IDEA window.
        if (focusGainStartTask != null) {
            focusGainStartTask?.cancel(false)
            focusGainStartTask = null
            pendingFocusProjectId = null
            pendingFocusProjectName = null
            pendingFocusProject = null
            notifyListeners()
        }

        if (isIdeaFocused) {
            onFocusLost()
        }
    }

    @Volatile
    private var pendingFocusProjectId: String? = null

    @Volatile
    private var pendingFocusProjectName: String? = null

    @Volatile
    private var pendingFocusProject: Project? = null

    private fun updatePendingProject(project: Project?) {
        if (project == null || project.isDisposed || project.isDefault) return
        val projectId = getProjectId(project)
        pendingFocusProjectId = projectId
        pendingFocusProjectName = project.name
        pendingFocusProject = project
        val state = FocusTimeState.getInstance()
        synchronized(state) {
            state.projectDisplayNames[projectId] = project.name
        }
    }

    private fun scheduleFocusGained(project: Project?) {
        updatePendingProject(project)

        log.info("IDEA window gained focus (starting ${FOCUS_GAIN_DELAY_MILLIS}ms delay), project: ${project?.name}")

        focusGainStartTask?.cancel(false)
        focusGainStartTask = scheduler.schedule(
            Runnable {
                try {
                    if (!isIdeaWindowFocused) {
                        focusGainStartTask = null
                        pendingFocusProjectId = null
                        pendingFocusProjectName = null
                        pendingFocusProject = null
                        notifyListeners()
                        return@Runnable
                    }
                    startTrackingAfterDelay()
                } catch (e: Exception) {
                    log.error("Error starting tracking after focus delay", e)
                }
            },
            FOCUS_GAIN_DELAY_MILLIS,
            TimeUnit.MILLISECONDS
        )

        notifyListeners()
    }

    private fun startTrackingAfterDelay() {
        val state = FocusTimeState.getInstance()

        // Don't start tracking if paused
        if (state.isPaused) {
            log.info("Focus delay elapsed but tracking is paused")
            focusGainStartTask = null
            notifyListeners()
            return
        }

        isIdeaFocused = true
        isIdlePaused = false
        focusGainStartTask = null
        lastUserActivityAt = System.currentTimeMillis()

        val now = System.currentTimeMillis()
        val projectId = pendingFocusProjectId
        val projectName = pendingFocusProjectName
        val project = pendingFocusProject
        pendingFocusProjectId = null
        pendingFocusProjectName = null
        pendingFocusProject = null

        // Update current active project reference
        currentActiveProject = project

        // Get current branch for the project
        val branch = getCurrentBranch(project)

        synchronized(state) {
            val todayKey = state.getTodayKey()
            if (state.sessionDate != todayKey) {
                state.sessionDate = todayKey
            }

            state.sessionStartTime = now
            state.focusSessionStartTime = now
            state.activeBranch = branch
            if (projectId != null) {
                state.activeProject = projectId
                if (projectName != null) {
                    state.projectDisplayNames[projectId] = projectName
                }
            }
        }

        notifyListeners()
    }

    private fun pauseForIdle(cutoffMillis: Long) {
        if (isIdlePaused) return
        if (!isIdeaFocused) return

        log.info("User idle detected, pausing tracking at $cutoffMillis")

        // Persist time only up to the idle cutoff, then stop tracking.
        saveFocusTime(nowMillis = cutoffMillis, continueTracking = false)

        val state = FocusTimeState.getInstance()
        synchronized(state) {
            state.sessionStartTime = null
            state.focusSessionStartTime = null
        }

        isIdlePaused = true
        isIdeaFocused = false

        notifyListeners()
    }

    private fun resumeFromIdle() {
        if (!isIdlePaused) return
        if (!isIdeaWindowFocused) return

        val state = FocusTimeState.getInstance()
        if (state.isPaused) return

        val window = lastIdeaWindow ?: KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
        val project = resolveProjectForWindow(window, WindowManager.getInstance().allProjectFrames)
        updatePendingProject(project)

        // Update current active project and get branch
        currentActiveProject = project
        val branch = getCurrentBranch(project)

        val now = System.currentTimeMillis()
        synchronized(state) {
            state.sessionStartTime = now
            state.focusSessionStartTime = now
            state.sessionDate = state.getTodayKey()
            state.activeBranch = branch
            pendingFocusProjectId?.let { state.activeProject = it }
        }

        pendingFocusProjectId = null
        pendingFocusProjectName = null
        pendingFocusProject = null

        isIdlePaused = false
        isIdeaFocused = true
        lastUserActivityAt = now

        notifyListeners()
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
        return openProjects.filterNot { it.isDefault }.singleOrNull()
    }

    private fun findActiveFrameWindow(projectFrames: Array<out IdeFrame>): Window? {
        for (frame in projectFrames) {
            val frameWindow = frame.component?.let(SwingUtilities::getWindowAncestor) ?: continue
            if (frameWindow.isActive) return frameWindow
        }
        return null
    }

    private fun resolveActiveProjectFromFrames(projectFrames: Array<out IdeFrame>): Project? {
        for (frame in projectFrames) {
            val project = frame.project ?: continue
            val frameWindow = frame.component?.let(SwingUtilities::getWindowAncestor) ?: continue
            if (frameWindow.isActive) return project
        }
        return null
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
            state.activeProject = project?.takeIf { !it.isDefault }?.let { p ->
                val projectId = getProjectId(p)
                state.projectDisplayNames[projectId] = p.name
                projectId
            } ?: state.activeProject
        }

        notifyListeners()
    }

    private fun onProjectChanged(project: Project) {
        if (project.isDefault) return
        val state = FocusTimeState.getInstance()

        val projectId = getProjectId(project)
        synchronized(state) {
            state.projectDisplayNames[projectId] = project.name
            if (state.activeProject == projectId) return
            if (state.isPaused) {
                state.activeProject = projectId
                currentActiveProject = project
                state.activeBranch = getCurrentBranch(project)
                return
            }
        }

        log.info("Project changed from ${state.activeProject} to $projectId")

        // Save time for previous project
        saveFocusTime()

        // Update current active project reference
        currentActiveProject = project

        // Get current branch for the new project
        val branch = getCurrentBranch(project)

        // Start tracking new project
        synchronized(state) {
            state.sessionStartTime = System.currentTimeMillis()
            state.activeProject = projectId
            state.activeBranch = branch
        }

        notifyListeners()
    }

    private fun onFocusLost() {
        if (focusLossGraceStopTask != null) return

        log.info("IDEA window lost focus (starting 2-minute grace period)")

        focusLossGraceStopTask = scheduler.schedule({
            try {
                log.info("Grace period elapsed, stopping tracking")
                isIdeaFocused = false
                isIdlePaused = false
                saveFocusTime()

                val state = FocusTimeState.getInstance()
                synchronized(state) {
                    state.focusSessionStartTime = null
                }

                focusLossGraceStopTask = null
                notifyListeners()
            } catch (e: Exception) {
                log.error("Error stopping tracking after grace period", e)
            }
        }, focusLossGraceMillis, TimeUnit.MILLISECONDS)

        notifyListeners()
    }

    fun togglePause() {
        val state = FocusTimeState.getInstance()

        val wasPaused = synchronized(state) { state.isPaused }

        if (wasPaused) {
            // Resume tracking (best-effort sync focus state; after sleep focus events may not fire).
            synchronized(state) { state.isPaused = false }

            isIdlePaused = false
            lastUserActivityAt = System.currentTimeMillis()

            val focusedWindow =
                KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                    ?: IdeFocusManager.getGlobalInstance().lastFocusedFrame?.component?.let(SwingUtilities::getWindowAncestor)
                    ?: lastIdeaWindow

            val projectFrames = WindowManager.getInstance().allProjectFrames
            val matchedProject = resolveProjectForWindow(focusedWindow, projectFrames)
            val activeFrameProject = resolveActiveProjectFromFrames(projectFrames)
            val effectiveProject = matchedProject ?: activeFrameProject

            val isIdea = effectiveProject != null || focusedWindow?.javaClass?.name?.let { className ->
                className.contains("intellij", ignoreCase = true) ||
                    className.contains("idea", ignoreCase = true) ||
                    className.contains("jetbrains", ignoreCase = true)
            } == true

            if (isIdea) {
                isIdeaWindowFocused = true
                lastIdeaWindow = focusedWindow ?: findActiveFrameWindow(projectFrames)

                focusLossGraceStopTask?.cancel(false)
                focusLossGraceStopTask = null
                focusGainStartTask?.cancel(false)
                focusGainStartTask = null

                updatePendingProject(effectiveProject)
                startTrackingAfterDelay()
                log.info("Tracking resumed (in focus)")
            } else {
                log.info("Tracking resumed (not in focus)")
            }
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
        saveFocusTime(nowMillis = System.currentTimeMillis(), continueTracking = isIdeaFocused)
    }

    private fun saveFocusTime(nowMillis: Long, continueTracking: Boolean) {
        val state = FocusTimeState.getInstance()
        val now = nowMillis
        val zone = ZoneId.systemDefault()

        synchronized(state) {
            val startTime = state.sessionStartTime ?: return
            if (state.isPaused) return

            val clampedStart = minOf(startTime, now)
            val projectId = state.activeProject
            val branch = state.activeBranch

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

                    // Also record branch-level time
                    state.recordBranchTime(projectId, branch, dateKey, elapsed)
                }

                totalSaved += elapsed
                cursor = segmentEnd
                date = date.plusDays(1)
            }

            state.sessionDate = LocalDate.now(zone).format(DateTimeFormatter.ISO_LOCAL_DATE)

            // Reset checkpoint start to now (continue tracking)
            if (continueTracking && !state.isPaused) {
                state.sessionStartTime = now
            } else {
                state.sessionStartTime = null
            }

            log.debug("Saved focus time: ${totalSaved}ms, project: $projectId, branch: $branch")
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

    private fun backfillProjectPathsFromRecentProjects() {
        if (recentProjectsBackfilled) return
        recentProjectsBackfilled = true

        val state = FocusTimeState.getInstance()
        val missingIds = LinkedHashSet<String>()
        synchronized(state) {
            val candidates = LinkedHashSet<String>()
            candidates.addAll(state.projectFocusTime.keys)
            candidates.addAll(state.aiProjectTime.keys)
            candidates.addAll(state.aiActiveSegments.keys)
            state.activeProject?.let(candidates::add)

            for (id in candidates) {
                if (id.startsWith("loc:") && state.projectPaths[id].isNullOrBlank()) {
                    missingIds.add(id)
                }
            }
        }

        if (missingIds.isEmpty()) return

        val recentPaths: List<String> = try {
            val clazz = Class.forName("com.intellij.ide.RecentProjectsManager")
            val getInstance = clazz.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 } ?: return
            val instance = getInstance.invoke(null) ?: return

            val getRecentPaths = clazz.methods.firstOrNull { it.name == "getRecentPaths" && it.parameterCount == 0 }
            val raw = getRecentPaths?.invoke(instance) ?: return

            when (raw) {
                is Collection<*> -> raw.filterIsInstance<String>()
                is Array<*> -> raw.filterIsInstance<String>()
                is Iterable<*> -> raw.filterIsInstance<String>()
                else -> emptyList()
            }
        } catch (e: Throwable) {
            log.debug("Unable to fetch recent project paths", e)
            emptyList()
        }

        if (recentPaths.isEmpty()) return

        val userHome = System.getProperty("user.home").orEmpty()

        fun expandUserHomeMacros(path: String): String {
            var result = path.trim()
            if (userHome.isNotBlank()) {
                result = result.replace("\$USER_HOME\$", userHome)
                if (result.startsWith("~/")) {
                    result = userHome.trimEnd('/') + "/" + result.removePrefix("~/")
                }
            }
            return result
        }

        fun normalizedPathCandidates(path: String): List<String> {
            val expanded = expandUserHomeMacros(path)
            val withoutTrailingSlash = expanded.trimEnd('/')
            return if (withoutTrailingSlash == expanded) listOf(expanded) else listOf(expanded, withoutTrailingSlash)
        }

        var updated = 0
        synchronized(state) {
            for (path in recentPaths) {
                if (path.isBlank()) continue

                for (candidate in normalizedPathCandidates(path)) {
                    if (candidate.isBlank()) continue
                    val projectId = "loc:" + Integer.toHexString(candidate.hashCode())
                    if (!missingIds.contains(projectId)) continue
                    state.projectPaths[projectId] = candidate
                    updated++
                    break
                }
            }
        }

        if (updated > 0) {
            log.info("Backfilled project paths: $updated")
            ApplicationManager.getApplication().invokeLater {
                try {
                    ApplicationManager.getApplication().saveSettings()
                } catch (_: Throwable) {
                    // ignore
                }
            }
            notifyListeners()
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
        val openProjects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }
        return openProjects.map { p ->
            val projectId = getProjectId(p)
            val state = FocusTimeState.getInstance()
            synchronized(state) {
                state.projectDisplayNames[projectId] = p.name
                p.basePath?.takeIf { it.isNotBlank() }?.let { state.projectPaths[projectId] = it }
            }
            FocusTimeState.ProjectInfo(id = projectId, name = p.name, path = p.basePath)
        }
    }

    fun getActiveProjectDisplayName(): String? {
        val state = FocusTimeState.getInstance()
        val projectId = synchronized(state) { state.activeProject } ?: return null
        return state.getProjectDisplayName(projectId)
    }

    fun getActiveProjectName(): String? = getActiveProjectDisplayName()

    fun getActiveBranch(): String? {
        val state = FocusTimeState.getInstance()
        return synchronized(state) { state.activeBranch }
    }

    override fun dispose() {
        log.info("Disposing focus tracking service")

        // Save final state, but do not keep an "active" session across IDE shutdown.
        // Otherwise, the next IDE start may attribute the whole offline gap to focus time.
        saveFocusTime(nowMillis = System.currentTimeMillis(), continueTracking = false)
        val state = FocusTimeState.getInstance()
        synchronized(state) {
            state.sessionStartTime = null
            state.focusSessionStartTime = null
        }

        // Remove listeners
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .removePropertyChangeListener("focusedWindow", focusPropertyListener)

        try {
            Toolkit.getDefaultToolkit().removeAWTEventListener(awtActivityListener)
        } catch (_: Throwable) {
            // ignore
        }

        // Shutdown scheduler
        periodicTask?.cancel(false)
        focusCheckTask?.cancel(false)
        focusLossGraceStopTask?.cancel(false)
        focusGainStartTask?.cancel(false)
        scheduler.shutdown()
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        listeners.clear()
    }

    companion object {
        private const val SYSTEM_SUSPEND_GAP_THRESHOLD_MILLIS = 30_000L
        private const val FOCUS_GAIN_DELAY_MILLIS = 3_000L
        private const val USER_IDLE_THRESHOLD_MILLIS = 60_000L

        fun getInstance(): FocusTrackingService =
            ApplicationManager.getApplication().getService(FocusTrackingService::class.java)
    }
}
