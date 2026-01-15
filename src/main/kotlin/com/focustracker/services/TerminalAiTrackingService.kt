package com.focustracker.services

import com.focustracker.state.FocusTimeState
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.ui.TerminalWidget
import com.jediterm.terminal.model.TerminalModelListener
import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class TerminalAiTrackingService : Disposable {

    private val log = Logger.getInstance(TerminalAiTrackingService::class.java)
    private val application: Application = ApplicationManager.getApplication()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "FocusTracker-TerminalAI").apply { isDaemon = true }
    }

    private var periodicTask: ScheduledFuture<*>? = null
    @Volatile
    private var lastHeartbeatAt: Long = 0L

    private val trackedProjects = ConcurrentHashMap.newKeySet<Project>()
    private val attachedWidgets = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val lastMarkedAtByProjectId = ConcurrentHashMap<String, Long>()
    private val jediSnapshots = Collections.synchronizedMap(WeakHashMap<JBTerminalWidget, JediSnapshot>())
    private val terminalRoots = Collections.synchronizedMap(WeakHashMap<Component, TerminalProjectInfo>())

    private val keyEventDispatcher = KeyEventDispatcher { e ->
        try {
            if (!shouldCountKeyEvent(e)) return@KeyEventDispatcher false

            val component = e.component ?: return@KeyEventDispatcher false
            val info = findTerminalProjectInfo(component) ?: return@KeyEventDispatcher false
            markAiActivity(projectId = info.projectId, projectName = info.projectName)
        } catch (_: Throwable) {
            // ignore
        }
        false
    }

    init {
        try {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyEventDispatcher)
        } catch (_: Throwable) {
            // ignore
        }

        // Best-effort flush to avoid retaining active segments under memory pressure.
        try {
            val lowMemoryWatcherClass = Class.forName("com.intellij.openapi.util.LowMemoryWatcher")
            val register = lowMemoryWatcherClass.methods.firstOrNull { m ->
                m.name == "register" && m.parameterTypes.size == 2
            }
            register?.invoke(
                null,
                Runnable { FocusTimeState.getInstance().flushExpiredAiSegments(System.currentTimeMillis()) },
                this
            )
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun startTracking(project: Project) {
        if (project.isDisposed) return
        trackedProjects.add(project)
        ensureStarted()

        // Best-effort early attach to avoid missing short-lived output bursts.
        application.invokeLater {
            if (!project.isDisposed) {
                scanAndAttach(project)
            }
        }
    }

    private fun ensureStarted() {
        if (periodicTask != null) return

        periodicTask = scheduler.scheduleAtFixedRate({
            try {
                tick()
            } catch (e: Exception) {
                log.warn("Terminal AI tracking tick failed", e)
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val last = lastHeartbeatAt
        lastHeartbeatAt = now
        if (last != 0L && now - last > SYSTEM_SUSPEND_GAP_THRESHOLD_MILLIS) {
            // Best-effort: cut AI grace segments at the last time we were running to avoid counting sleep.
            FocusTimeState.getInstance().endAiSegmentsAt(last)
        }

        val state = FocusTimeState.getInstance()
        state.flushExpiredAiSegments(now)

        // Prefer openProjects to avoid retaining disposed projects in the set.
        val projects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        if (projects.isEmpty()) return

        // TerminalView and widgets are UI-related; access them on EDT to avoid thread issues.
        if (application.isDispatchThread) {
            projects.forEach(::scanAndAttach)
        } else {
            application.invokeAndWait {
                projects.forEach(::scanAndAttach)
            }
        }

        trackedProjects.removeIf { it.isDisposed }
    }

    private fun scanAndAttach(project: Project) {
        val widgets = getTerminalWidgets(project) ?: return
        widgets.forEach { widget ->
            attach(project, widget)
        }
    }

    private fun attach(project: Project, widget: Any) {
        synchronized(attachedWidgets) {
            if (!attachedWidgets.add(widget)) return
        }

        when (widget) {
            is JBTerminalWidget -> attachJediTerm(project, widget)
            is TerminalWidget -> attachBlockTerminal(project, widget)
            else -> {
                synchronized(attachedWidgets) {
                    attachedWidgets.remove(widget)
                }
            }
        }
    }

    private fun attachJediTerm(project: Project, widget: JBTerminalWidget) {
        registerTerminalRoot(widget, project)

        val buffer = widget.terminalPanel.terminalTextBuffer
        jediSnapshots[widget] = JediSnapshot.from(widget)
        val listener = TerminalModelListener {
            if (hasJediTermVisibleChanges(widget)) {
                markAiActivity(projectId = getProjectId(project), projectName = project.name)
            }
        }

        buffer.addModelListener(listener)

        val keyHandler = java.util.function.Consumer<KeyEvent> { e ->
            if (shouldCountKeyEvent(e)) {
                markAiActivity(projectId = getProjectId(project), projectName = project.name)
            }
        }
        widget.terminalPanel.addPreKeyEventHandler(keyHandler)

        Disposer.register(widget, Disposable {
            buffer.removeModelListener(listener)
            jediSnapshots.remove(widget)
            unregisterTerminalRoot(widget)
            synchronized(attachedWidgets) {
                attachedWidgets.remove(widget)
            }
        })
    }

    private fun attachBlockTerminal(project: Project, widget: TerminalWidget) {
        val view = getTerminalWidgetContentView(widget) ?: run {
            synchronized(attachedWidgets) {
                attachedWidgets.remove(widget)
            }
            return
        }

        val root = widget.component
        registerTerminalRoot(root, project)

        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (shouldCountDocumentEvent(event)) {
                    markAiActivity(projectId = getProjectId(project), projectName = project.name)
                }
            }
        }

        val disposable = Disposer.newDisposable("FocusTracker-TerminalAI-BlockListener")
        Disposer.register(widget, disposable)

        try {
            val attached = attachDocumentListenerToBlockOrSimpleTerminal(view, listener, disposable)
            if (!attached) {
                log.debug("Unable to attach terminal output listener for ${widget.javaClass.name}")
                Disposer.dispose(disposable)
                unregisterTerminalRoot(root)
                synchronized(attachedWidgets) {
                    attachedWidgets.remove(widget)
                }
                return
            }
        } catch (e: Exception) {
            log.debug("Failed to attach block terminal document listener", e)
            Disposer.dispose(disposable)
            unregisterTerminalRoot(root)
            synchronized(attachedWidgets) {
                attachedWidgets.remove(widget)
            }
            return
        }

        val focusComponent = widget.preferredFocusableComponent ?: widget.component
        val keyListener = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (shouldCountKeyEvent(e)) {
                    markAiActivity(projectId = getProjectId(project), projectName = project.name)
                }
            }
        }
        focusComponent.addKeyListener(keyListener)

        Disposer.register(widget, Disposable {
            focusComponent.removeKeyListener(keyListener)
            unregisterTerminalRoot(root)
            synchronized(attachedWidgets) {
                attachedWidgets.remove(widget)
            }
        })
    }

    private fun markAiActivity(projectId: String, projectName: String? = null) {
        val now = System.currentTimeMillis()

        val lastMarkedAt = lastMarkedAtByProjectId.put(projectId, now) ?: 0L
        if (now - lastMarkedAt < 1_000L) return

        FocusTimeState.getInstance().recordAiActivity(projectId = projectId, projectName = projectName, nowMillis = now)
    }

    private fun shouldCountKeyEvent(e: KeyEvent): Boolean {
        if (e.id != KeyEvent.KEY_PRESSED) return false
        return when (e.keyCode) {
            KeyEvent.VK_SHIFT,
            KeyEvent.VK_CONTROL,
            KeyEvent.VK_ALT,
            KeyEvent.VK_META,
            KeyEvent.VK_CAPS_LOCK,
            KeyEvent.VK_NUM_LOCK,
            KeyEvent.VK_SCROLL_LOCK -> false
            else -> true
        }
    }

    private fun shouldCountDocumentEvent(event: DocumentEvent): Boolean {
        val newText = event.newFragment
        val oldText = event.oldFragment

        // Count when something actually changes in the rendered output:
        // - any non-whitespace char
        // - or newline (Enter/output line breaks)
        fun CharSequence.hasRelevantChars(): Boolean {
            for (i in indices) {
                val c = this[i]
                if (c == '\n') return true
                if (!c.isWhitespace()) return true
            }
            return false
        }

        return newText.isNotEmpty() && newText.hasRelevantChars() ||
            oldText.isNotEmpty() && oldText.hasRelevantChars()
    }

    private fun hasJediTermVisibleChanges(widget: JBTerminalWidget): Boolean {
        val buffer = widget.terminalPanel.terminalTextBuffer
        val historyLines = buffer.historyLinesCount
        val screen = buffer.screenLines
        val next = JediSnapshot(historyLinesCount = historyLines, screenLength = screen.length, screenHash = screen.hashCode())

        val prev = jediSnapshots[widget]
        if (prev == null || prev != next) {
            jediSnapshots[widget] = next
            return true
        }
        return false
    }

    private data class JediSnapshot(
        val historyLinesCount: Int,
        val screenLength: Int,
        val screenHash: Int
    ) {
        companion object {
            fun from(widget: JBTerminalWidget): JediSnapshot {
                val buffer = widget.terminalPanel.terminalTextBuffer
                val screen = buffer.screenLines
                return JediSnapshot(
                    historyLinesCount = buffer.historyLinesCount,
                    screenLength = screen.length,
                    screenHash = screen.hashCode()
                )
            }
        }
    }

    private fun getProjectId(project: Project): String {
        val locationHash = project.locationHash
        return if (locationHash.isNotBlank()) {
            "loc:$locationHash"
        } else {
            "name:${project.name}"
        }
    }

    private data class TerminalProjectInfo(
        val projectId: String,
        val projectName: String?
    )

    private fun registerTerminalRoot(component: Component, project: Project) {
        terminalRoots[component] = TerminalProjectInfo(projectId = getProjectId(project), projectName = project.name)
    }

    private fun unregisterTerminalRoot(component: Component) {
        terminalRoots.remove(component)
    }

    private fun findTerminalProjectInfo(component: Component): TerminalProjectInfo? {
        synchronized(terminalRoots) {
            for ((root, info) in terminalRoots) {
                if (javax.swing.SwingUtilities.isDescendingFrom(component, root)) return info
            }
        }

        if (!isProbablyTerminalComponent(component)) return null

        val project = try {
            val dataContext = DataManager.getInstance().getDataContext(component)
            CommonDataKeys.PROJECT.getData(dataContext)
        } catch (_: Throwable) {
            null
        } ?: return null

        if (project.isDisposed) return null
        return TerminalProjectInfo(projectId = getProjectId(project), projectName = project.name)
    }

    private fun isProbablyTerminalComponent(component: Component): Boolean {
        var current: Component? = component
        while (current != null) {
            val name = current.javaClass.name.lowercase()
            if (name.contains("terminal") || name.contains("jediterm")) return true
            current = current.parent
        }
        return false
    }

    private fun getTerminalWidgets(project: Project): Set<Any>? {
        return try {
            val terminalViewClass = Class.forName("org.jetbrains.plugins.terminal.TerminalView")
            val getInstance = terminalViewClass.getMethod("getInstance", Project::class.java)
            val terminalView = getInstance.invoke(null, project) ?: return null
            val getWidgets = terminalViewClass.getMethod("getWidgets")
            @Suppress("UNCHECKED_CAST")
            (getWidgets.invoke(terminalView) as? Set<*>)?.filterNotNull()?.toSet()
        } catch (_: ClassNotFoundException) {
            null
        } catch (e: Exception) {
            log.debug("Unable to access TerminalView widgets via reflection", e)
            null
        }
    }

    private fun getTerminalWidgetContentView(widget: TerminalWidget): Any? {
        // New terminal implementations keep the view in an internal property; Kotlin exposes it via access$getView$p.
        val clazz = widget.javaClass
        val accessMethod = clazz.methods.firstOrNull { m ->
            m.name.startsWith("access\$getView") && m.parameterTypes.size == 1 && m.parameterTypes[0].isAssignableFrom(clazz)
        } ?: clazz.methods.firstOrNull { m ->
            m.name == "access\$getView\$p" && m.parameterTypes.size == 1
        } ?: return null

        return try {
            accessMethod.invoke(null, widget)
        } catch (e: Exception) {
            log.debug("Unable to access terminal content view from ${clazz.name}", e)
            null
        }
    }

    private fun attachDocumentListenerToBlockOrSimpleTerminal(
        contentView: Any,
        listener: DocumentListener,
        parentDisposable: Disposable
    ): Boolean {
        // Block terminal: view.getOutputView().getController().addDocumentListener(listener, disposable)
        try {
            val getOutputView = contentView.javaClass.methods.firstOrNull { it.name == "getOutputView" }
            if (getOutputView != null) {
                val outputView = getOutputView.invoke(contentView) ?: return false
                val getController = outputView.javaClass.methods.firstOrNull { it.name == "getController" } ?: return false
                val outputController = getController.invoke(outputView) ?: return false
                val method = outputController.javaClass.methods.firstOrNull { m ->
                    m.name == "addDocumentListener" &&
                        m.parameterTypes.size == 2 &&
                        DocumentListener::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                        Disposable::class.java.isAssignableFrom(m.parameterTypes[1])
                } ?: return false
                method.invoke(outputController, listener, parentDisposable)
                return true
            }
        } catch (e: Exception) {
            log.debug("Block terminal listener attach failed for ${contentView.javaClass.name}", e)
        }

        // Simple/alternate buffer terminal: view.getController().getDocument().addDocumentListener(listener, disposable)
        try {
            val getController = contentView.javaClass.methods.firstOrNull { it.name == "getController" } ?: return false
            val controller = getController.invoke(contentView) ?: return false
            val getDocument = controller.javaClass.methods.firstOrNull { it.name == "getDocument" } ?: return false
            val document = getDocument.invoke(controller) as? com.intellij.openapi.editor.Document ?: return false
            document.addDocumentListener(listener, parentDisposable)
            return true
        } catch (e: Exception) {
            log.debug("Simple terminal listener attach failed for ${contentView.javaClass.name}", e)
        }

        return false
    }

    override fun dispose() {
        try {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyEventDispatcher)
        } catch (_: Throwable) {
            // ignore
        }

        periodicTask?.cancel(false)
        scheduler.shutdown()
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        FocusTimeState.getInstance().flushExpiredAiSegments(System.currentTimeMillis())
        trackedProjects.clear()
        synchronized(attachedWidgets) {
            attachedWidgets.clear()
        }
        lastMarkedAtByProjectId.clear()
        synchronized(terminalRoots) {
            terminalRoots.clear()
        }
    }

    companion object {
        private const val SYSTEM_SUSPEND_GAP_THRESHOLD_MILLIS = 30_000L

        fun getInstance(): TerminalAiTrackingService =
            ApplicationManager.getApplication().getService(TerminalAiTrackingService::class.java)
    }
}
