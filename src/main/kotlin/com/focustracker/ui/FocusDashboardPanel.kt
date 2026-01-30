package com.focustracker.ui

import com.focustracker.services.FocusTrackingService
import com.focustracker.state.FocusTimeState
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.SearchTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.time.LocalDate
import java.time.format.TextStyle
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import javax.swing.*
import javax.swing.Scrollable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class FocusDashboardPanel : JPanel(BorderLayout()), Disposable {

    private val todayTimeLabel = createTimeLabel()
    private val sessionTimeLabel = createTimeLabel()
    private val periodTotalLabel = createTimeLabel()
    private val allTimeTotalLabel = createTimeLabel()
    private val statusLabel = JBLabel()
    private val statusIndicator = JPanel()
    private val pauseButton = JButton()

    private val projectFilterField = SearchTextField().apply {
        textEditor.emptyText.text = "Filter projects (name or path)"
    }

    private val periodOptions = arrayOf("5 days", "7 days", "14 days", "30 days")
    private val periodDays = intArrayOf(5, 7, 14, 30)
    private val periodComboBox = com.intellij.openapi.ui.ComboBox(periodOptions)
    private var selectedPeriodDays = 7

    private val historyFilterComboBox = com.intellij.openapi.ui.ComboBox(arrayOf("Mine"))
    private var selectedHistoryFilter = HistoryFilter.MINE

    private val periodChartPanel = PeriodChartPanel()
    private val projectsTableModel = ProjectsTableModel()
    private val projectsTable = JBTable(projectsTableModel)
    private val aiProjectsTableModel = ProjectsTableModel()
    private val aiProjectsTable = JBTable(aiProjectsTableModel)

    private val aiSectionLabel = createSectionLabel("AI")
    private val mainScrollPane: JBScrollPane
    private val aiProjectsPanel = createProjectsPanel(aiProjectsTable)

    private var lastHeavyRefreshAt: Long = 0L
    private var lastFilterText: String = ""
    private var lastHeavyPeriodDays: Int = selectedPeriodDays
    private var lastHeavyHistoryFilter: HistoryFilter = selectedHistoryFilter

    @Volatile
    private var recentPathsCacheAt: Long = 0L
    @Volatile
    private var recentPathsByProjectIdCache: Map<String, String> = emptyMap()

    private val updateListener: () -> Unit = { refreshData() }

    init {
        border = JBUI.Borders.empty(16)
        background = UIUtil.getPanelBackground()

        // Setup period selector
        periodComboBox.selectedIndex = 1 // Default to 7 days
        periodComboBox.addActionListener {
            selectedPeriodDays = periodDays[periodComboBox.selectedIndex]
            refreshData()
        }

        // Setup history filter
        historyFilterComboBox.selectedIndex = 0 // Default to Mine (AI disabled by default)
        historyFilterComboBox.addActionListener {
            selectedHistoryFilter = when (historyFilterComboBox.selectedItem as? String) {
                "All" -> HistoryFilter.ALL
                "AI" -> HistoryFilter.AI
                else -> HistoryFilter.MINE
            }
            refreshData()
            if (selectedHistoryFilter == HistoryFilter.AI) {
                scrollToAiSection()
            }
        }

        projectFilterField.textEditor.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = refreshData()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = refreshData()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = refreshData()
        })

        val contentPanel = object : JPanel(), Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

            override fun getScrollableUnitIncrement(
                visibleRect: Rectangle,
                orientation: Int,
                direction: Int
            ): Int = 16

            override fun getScrollableBlockIncrement(
                visibleRect: Rectangle,
                orientation: Int,
                direction: Int
            ): Int = visibleRect.height - 16

            override fun getScrollableTracksViewportWidth(): Boolean = true

            override fun getScrollableTracksViewportHeight(): Boolean = false
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Header with status and pause button
        contentPanel.add(createHeaderPanel())
        contentPanel.add(Box.createVerticalStrut(20))

        // Current stats cards
        contentPanel.add(createStatsCardsPanel())
        contentPanel.add(Box.createVerticalStrut(24))

        // Projects section (with branch grouping)
        contentPanel.add(createProjectsHeaderPanel())
        contentPanel.add(Box.createVerticalStrut(8))
        contentPanel.add(createProjectsPanel(projectsTable))
        contentPanel.add(Box.createVerticalStrut(16))

        contentPanel.add(aiSectionLabel)
        contentPanel.add(Box.createVerticalStrut(8))
        contentPanel.add(aiProjectsPanel)
        contentPanel.add(Box.createVerticalStrut(24))

        // Period chart with selector
        contentPanel.add(createPeriodHeaderPanel())
        contentPanel.add(Box.createVerticalStrut(8))
        contentPanel.add(periodChartPanel)

        mainScrollPane = JBScrollPane(contentPanel).apply {
            border = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(mainScrollPane, BorderLayout.CENTER)

        // Register for updates
        FocusTrackingService.getInstance().addListener(updateListener)
        Disposer.register(this, Disposable {
            FocusTrackingService.getInstance().removeListener(updateListener)
        })

        val aiEnabled = FocusTimeState.getInstance().isAiTrackingEnabled
        configureHistoryFilterOptions(aiEnabled)
        aiSectionLabel.isVisible = aiEnabled
        aiProjectsPanel.isVisible = aiEnabled
        refreshData()
    }

    private fun createProjectsHeaderPanel(): JPanel {
        return JPanel(GridBagLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 30)
            preferredSize = Dimension(0, 30)
            alignmentX = Component.LEFT_ALIGNMENT

            projectFilterField.apply {
                minimumSize = Dimension(0, 28)
                preferredSize = Dimension(360, 28)
                maximumSize = Dimension(Int.MAX_VALUE, 28)
            }

            val labelConstraints = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                weightx = 0.0
                fill = GridBagConstraints.NONE
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 0, 8)
            }

            val fieldConstraints = GridBagConstraints().apply {
                gridx = 1
                gridy = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            }

            add(createSectionLabel("Projects"), labelConstraints)
            add(projectFilterField, fieldConstraints)
        }
    }

    private fun configureHistoryFilterOptions(aiEnabled: Boolean) {
        val items = if (aiEnabled) arrayOf("All", "Mine", "AI") else arrayOf("Mine")
        val current = historyFilterComboBox.selectedItem as? String

        historyFilterComboBox.model = DefaultComboBoxModel(items)

        val desired = when {
            !aiEnabled -> "Mine"
            current in items -> current
            selectedHistoryFilter == HistoryFilter.AI -> "AI"
            selectedHistoryFilter == HistoryFilter.ALL -> "All"
            else -> "Mine"
        }

        historyFilterComboBox.selectedItem = desired
        selectedHistoryFilter = when (desired) {
            "All" -> HistoryFilter.ALL
            "AI" -> HistoryFilter.AI
            else -> HistoryFilter.MINE
        }
    }

    private fun scrollToAiSection() {
        if (!aiSectionLabel.isVisible) return
        SwingUtilities.invokeLater {
            val rect = SwingUtilities.convertRectangle(aiSectionLabel.parent, aiSectionLabel.bounds, mainScrollPane.viewport.view)
            mainScrollPane.viewport.scrollRectToVisible(rect)
        }
    }

    private fun createHeaderPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 40)

            // Left side: status indicator
            val statusPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false

                statusIndicator.apply {
                    preferredSize = Dimension(12, 12)
                    background = JBColor.GREEN
                    border = BorderFactory.createLineBorder(JBColor.border(), 1)
                }

                add(statusIndicator)
                add(Box.createHorizontalStrut(8))
                add(statusLabel)
            }

            // Right side: pause button
            pauseButton.apply {
                icon = AllIcons.Actions.Pause
                toolTipText = "Pause tracking"
                isFocusable = false
                addActionListener {
                    FocusTrackingService.getInstance().togglePause()
                }
            }

            add(statusPanel, BorderLayout.WEST)
            add(pauseButton, BorderLayout.EAST)
        }
    }

    private fun createStatsCardsPanel(): JPanel {
        return JPanel(GridLayout(2, 2, 12, 12)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 180)

            add(createStatCard("Today", todayTimeLabel))
            add(createStatCard("Current Session", sessionTimeLabel))
            add(createStatCard("Selected Period", periodTotalLabel))
            add(createStatCard("All Time", allTimeTotalLabel))
        }
    }

    private fun createPeriodHeaderPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 30)

            val label = JBLabel("History").apply {
                font = font.deriveFont(Font.BOLD, 14f)
                foreground = JBColor.foreground()
            }

            val controlsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false
            }

            historyFilterComboBox.apply {
                preferredSize = Dimension(90, 28)
            }

            periodComboBox.apply {
                preferredSize = Dimension(100, 28)
            }

            add(label, BorderLayout.WEST)

            controlsPanel.add(historyFilterComboBox)
            controlsPanel.add(periodComboBox)
            add(controlsPanel, BorderLayout.EAST)
        }
    }

    private fun createProjectsPanel(table: JBTable): JPanel {
        table.apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = 28
            tableHeader.reorderingAllowed = false

            val model = table.model as? ProjectsTableModel
            val headerBg = JBColor(Color(240, 240, 245), Color(55, 55, 60))

            // Custom renderer for project/branch name column
            val nameRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable?, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    border = JBUI.Borders.empty(0, 8)

                    val modelRow = if (table != null) table.convertRowIndexToModel(row) else row
                    val isHeader = model?.isHeaderRow(modelRow) == true
                    font = if (isHeader) {
                        font.deriveFont(Font.BOLD)
                    } else {
                        font.deriveFont(Font.PLAIN)
                    }
                    foreground = if (isHeader) {
                        JBColor.foreground()
                    } else {
                        JBColor.gray
                    }
                    if (!isSelected) {
                        background = if (isHeader) headerBg else UIUtil.getTableBackground()
                    }
                    isOpaque = true
                    return this
                }
            }

            // Custom renderer for time columns
            val timeRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable?, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    horizontalAlignment = SwingConstants.RIGHT
                    border = JBUI.Borders.empty(0, 8)

                    val modelRow = if (table != null) table.convertRowIndexToModel(row) else row
                    val isHeader = model?.isHeaderRow(modelRow) == true
                    font = if (isHeader) {
                        font.deriveFont(Font.BOLD)
                    } else {
                        font.deriveFont(Font.PLAIN)
                    }
                    foreground = if (isHeader) {
                        JBColor.foreground()
                    } else {
                        JBColor.gray
                    }
                    if (!isSelected) {
                        background = if (isHeader) headerBg else UIUtil.getTableBackground()
                    }
                    isOpaque = true
                    return this
                }
            }

            columnModel.getColumn(0).cellRenderer = nameRenderer
            columnModel.getColumn(1).cellRenderer = timeRenderer
            columnModel.getColumn(2).cellRenderer = timeRenderer

            // Set column widths
            columnModel.getColumn(0).preferredWidth = 200
            columnModel.getColumn(1).preferredWidth = 100
            columnModel.getColumn(2).preferredWidth = 100

            // Expand/collapse branches when clicking a project header row.
            if (model != null) {
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount != 1) return
                        val viewRow = rowAtPoint(e.point)
                        if (viewRow < 0) return
                        val modelRow = convertRowIndexToModel(viewRow)
                        if (!model.isHeaderRow(modelRow)) return
                        if (!model.isExpandableHeaderRow(modelRow)) return
                        model.toggleExpanded(modelRow)
                    }
                })

                addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                    override fun mouseMoved(e: java.awt.event.MouseEvent) {
                        val viewRow = rowAtPoint(e.point)
                        if (viewRow < 0) {
                            cursor = Cursor.getDefaultCursor()
                            return
                        }
                        val modelRow = convertRowIndexToModel(viewRow)
                        cursor = if (model.isExpandableHeaderRow(modelRow)) {
                            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        } else {
                            Cursor.getDefaultCursor()
                        }
                    }
                })
            }
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 400)
            preferredSize = Dimension(400, 300)

            val scrollPane = JBScrollPane(table).apply {
                border = BorderFactory.createLineBorder(JBColor.border(), 1)
            }
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    private fun createStatCard(title: String, valueLabel: JLabel): JPanel {
        return JPanel(BorderLayout()).apply {
            background = JBColor(Color(245, 245, 250), Color(50, 50, 55))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(12)
            )

            val titleLabel = JBLabel(title).apply {
                foreground = JBColor.gray
                font = font.deriveFont(12f)
            }

            add(titleLabel, BorderLayout.NORTH)
            add(valueLabel, BorderLayout.CENTER)
        }
    }

    private fun createTimeLabel(): JLabel {
        return JBLabel("--:--:--").apply {
            font = font.deriveFont(Font.BOLD, 24f)
            foreground = JBColor.foreground()
        }
    }

    private fun createSectionLabel(text: String): JLabel {
        return JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, 14f)
            foreground = JBColor.foreground()
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    private fun refreshData() {
        val state = FocusTimeState.getInstance()
        val service = FocusTrackingService.getInstance()
        val knownProjects = service.getOpenProjectsInfo()
        val aiEnabled = state.isAiTrackingEnabled
        val projectFilter = projectFilterField.text.trim().lowercase()
        val filterChanged = projectFilter != lastFilterText
        val heavyPeriodChanged = selectedPeriodDays != lastHeavyPeriodDays
        val heavyHistoryChanged = selectedHistoryFilter != lastHeavyHistoryFilter
        val filterFocused = projectFilterField.textEditor.hasFocus()

        val recentPathsByProjectId = if (projectFilter.isBlank()) emptyMap() else getRecentProjectPathsById()
        val filteredProjectIds = if (projectFilter.isBlank()) {
            emptySet()
        } else {
            resolveFilteredProjectIds(state, knownProjects, recentPathsByProjectId, projectFilter)
        }

        // Update status
        val isPaused = service.isPaused()
        val isFocused = service.isFocused()
        val activeBranch = service.getActiveBranch()

        val statusText = when {
            isPaused -> "Paused"
            isFocused && activeBranch != null -> "Tracking ($activeBranch)"
            isFocused -> "Tracking"
            else -> "Not in focus"
        }

        statusLabel.text = statusText
        statusLabel.foreground = when {
            isPaused -> JBColor.ORANGE
            isFocused -> JBColor(Color(34, 139, 34), Color(50, 205, 50))
            else -> JBColor.gray
        }

        statusIndicator.background = when {
            isPaused -> JBColor.ORANGE
            isFocused -> JBColor.GREEN
            else -> JBColor.GRAY
        }

        // Update pause button
        pauseButton.icon = if (isPaused) AllIcons.Actions.Resume else AllIcons.Actions.Pause
        pauseButton.toolTipText = if (isPaused) "Resume tracking" else "Pause tracking"

        // Get period data (optionally filtered by project substring).
        val focusPeriodData = if (projectFilter.isBlank()) {
            state.getPeriodFocusTime(selectedPeriodDays)
        } else {
            state.getPeriodFocusTimeForProjects(selectedPeriodDays, filteredProjectIds)
        }

        val aiPeriodData = if (!aiEnabled) {
            emptyMap()
        } else if (projectFilter.isBlank()) {
            state.getAiPeriodTime(selectedPeriodDays)
        } else {
            state.getAiPeriodTimeForProjects(selectedPeriodDays, filteredProjectIds)
        }

        val periodData = when (selectedHistoryFilter) {
            HistoryFilter.ALL -> focusPeriodData.mapValues { (k, v) -> v + (aiPeriodData[k] ?: 0L) }
            HistoryFilter.MINE -> focusPeriodData
            HistoryFilter.AI -> aiPeriodData
        }

        val todayMine = if (projectFilter.isBlank()) {
            state.getTodayFocusTime()
        } else {
            state.getTodayFocusTimeForProjects(filteredProjectIds)
        }

        val todayAi = if (!aiEnabled) {
            0L
        } else if (projectFilter.isBlank()) {
            state.getAiTodayTime()
        } else {
            state.getAiTodayTimeForProjects(filteredProjectIds)
        }

        val todayMillis = when (selectedHistoryFilter) {
            HistoryFilter.ALL -> todayMine + todayAi
            HistoryFilter.MINE -> todayMine
            HistoryFilter.AI -> todayAi
        }

        val periodMillis = periodData.values.sum()

        val allTimeMine = if (projectFilter.isBlank()) {
            state.getTotalFocusTime()
        } else {
            state.getTotalFocusTimeForProjects(filteredProjectIds)
        }

        val allTimeAi = if (!aiEnabled) {
            0L
        } else if (projectFilter.isBlank()) {
            state.getAiTotalTime()
        } else {
            state.getAiTotalTimeForProjects(filteredProjectIds)
        }

        val allTimeMillis = when (selectedHistoryFilter) {
            HistoryFilter.ALL -> allTimeMine + allTimeAi
            HistoryFilter.MINE -> allTimeMine
            HistoryFilter.AI -> allTimeAi
        }

        // Update stats
        todayTimeLabel.text = formatDuration(todayMillis)
        sessionTimeLabel.text = formatDuration(service.getCurrentSessionTime())
        periodTotalLabel.text = formatDuration(periodMillis)
        allTimeTotalLabel.text = formatDuration(allTimeMillis)

        val now = System.currentTimeMillis()
        val refreshInterval = if (filterFocused) {
            HEAVY_REFRESH_INTERVAL_FOCUSED_MILLIS
        } else {
            HEAVY_REFRESH_INTERVAL_MILLIS
        }

        val shouldHeavyRefresh =
            filterChanged || heavyPeriodChanged || heavyHistoryChanged || (now - lastHeavyRefreshAt >= refreshInterval)

        if (shouldHeavyRefresh) {
            // Update projects tables
            val mineRows = state.getAllProjectsStats(knownProjects)
            val aiRows = if (aiEnabled) state.getAiProjectsStats(knownProjects) else emptyList()

            val mineFilteredRows = if (projectFilter.isBlank()) {
                mineRows
            } else {
                mineRows.filter { it.id != "__unassigned__" && filteredProjectIds.contains(it.id) }
            }

            val aiFilteredRows = if (projectFilter.isBlank()) {
                aiRows
            } else {
                aiRows.filter { filteredProjectIds.contains(it.id) }
            }

            projectsTableModel.updateData(mineFilteredRows, state, service.getActiveProjectId(), activeBranch)
            if (aiEnabled) {
                aiProjectsTableModel.updateData(aiFilteredRows, state, service.getActiveProjectId(), activeBranch)
            } else {
                aiProjectsTableModel.updateData(emptyList(), state, service.getActiveProjectId(), activeBranch)
            }

            // Update chart
            periodChartPanel.updateData(periodData, selectedPeriodDays)

            lastHeavyRefreshAt = now
            lastFilterText = projectFilter
            lastHeavyPeriodDays = selectedPeriodDays
            lastHeavyHistoryFilter = selectedHistoryFilter
        }
    }

    private fun resolveFilteredProjectIds(
        state: FocusTimeState,
        knownProjects: List<FocusTimeState.ProjectInfo>,
        recentPathsByProjectId: Map<String, String>,
        filterLower: String
    ): Set<String> {
        val candidates = LinkedHashSet<String>()
        candidates.addAll(knownProjects.map { it.id })
        synchronized(state) {
            candidates.addAll(state.projectFocusTime.keys)
            candidates.addAll(state.aiProjectTime.keys)
            candidates.addAll(state.aiActiveSegments.keys)
            state.activeProject?.let(candidates::add)
        }

        return candidates.filterTo(LinkedHashSet()) { projectId ->
            if (projectId == "__unassigned__") return@filterTo false
            val name = state.getProjectDisplayName(projectId).lowercase()
            if (name.contains(filterLower)) return@filterTo true

            val path = (
                state.getProjectPath(projectId)
                    ?: recentPathsByProjectId[projectId]
                    ?: knownProjects.firstOrNull { it.id == projectId }?.path
                )
                ?.lowercase()
                ?: ""
            path.contains(filterLower)
        }
    }

    private fun getRecentProjectPathsById(): Map<String, String> {
        val now = System.currentTimeMillis()
        val cachedAt = recentPathsCacheAt
        if (now - cachedAt <= RECENT_PATHS_CACHE_TTL_MILLIS && recentPathsByProjectIdCache.isNotEmpty()) {
            return recentPathsByProjectIdCache
        }

        val optionsDir = try {
            Path.of(PathManager.getOptionsPath())
        } catch (_: Throwable) {
            return recentPathsByProjectIdCache
        }
        val file = optionsDir.resolve("recentProjects.xml")
        if (!Files.exists(file)) return recentPathsByProjectIdCache

        val userHome = System.getProperty("user.home").orEmpty()

        fun expandUserHomeMacros(path: String): String {
            var result = path.trim()
            if (userHome.isNotBlank()) {
                result = result.replace("\$USER_HOME\$", userHome)
                if (result.startsWith("~/")) {
                    result = userHome.trimEnd('/') + "/" + result.removePrefix("~/")
                }
            }
            return result.trimEnd('/')
        }

        val map = LinkedHashMap<String, String>()
        val entryRegex = Regex("""<entry\s+key="([^"]+)"""")
        try {
            Files.newBufferedReader(file).use { reader ->
                reader.lineSequence().forEach { line ->
                    val match = entryRegex.find(line) ?: return@forEach
                    val raw = match.groupValues[1]
                    if (raw.isBlank()) return@forEach

                    val expanded = expandUserHomeMacros(raw)
                    // Keep only absolute or user-home based paths.
                    if (!expanded.startsWith("/") && !expanded.startsWith(userHome)) return@forEach

                    val projectId = "loc:" + Integer.toHexString(expanded.hashCode())
                    map.putIfAbsent(projectId, expanded)
                }
            }
        } catch (_: Throwable) {
            return recentPathsByProjectIdCache
        }

        recentPathsByProjectIdCache = map
        recentPathsCacheAt = now
        return map
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }

    override fun dispose() {
        // Cleanup handled by Disposer
    }

    private enum class HistoryFilter {
        ALL,
        MINE,
        AI
    }

    companion object {
        private const val HEAVY_REFRESH_INTERVAL_MILLIS = 2_000L
        private const val HEAVY_REFRESH_INTERVAL_FOCUSED_MILLIS = 5_000L
        private const val RECENT_PATHS_CACHE_TTL_MILLIS = 10_000L
    }
}

class ProjectsTableModel : AbstractTableModel() {

    private var rows: List<DisplayRow> = emptyList()
    private val unassignedProjectId = "__unassigned__"
    private val expandedProjectIds: MutableSet<String> = LinkedHashSet()
    private var lastStats: List<FocusTimeState.ProjectStatsRow> = emptyList()
    private var lastState: FocusTimeState? = null
    private var lastActiveProjectId: String? = null
    private var lastActiveBranch: String? = null

    sealed class DisplayRow {
        data class ProjectHeader(
            val id: String,
            val name: String,
            val todayTime: Long,
            val totalTime: Long,
            val isActive: Boolean,
            val hasBranches: Boolean,
            val isExpanded: Boolean
        ) : DisplayRow()

        data class BranchRow(
            val projectId: String,
            val branch: String,
            val todayTime: Long,
            val totalTime: Long,
            val isActiveBranch: Boolean
        ) : DisplayRow()
    }

    fun updateData(
        stats: List<FocusTimeState.ProjectStatsRow>,
        state: FocusTimeState,
        activeProjectId: String?,
        activeBranch: String?
    ) {
        lastStats = stats
        lastState = state
        lastActiveProjectId = activeProjectId
        lastActiveBranch = activeBranch
        rebuildRows()
    }

    private fun rebuildRows() {
        val state = lastState ?: run {
            rows = emptyList()
            fireTableDataChanged()
            return
        }

        val result = mutableListOf<DisplayRow>()

        // Sort projects: active first, then by today time
        val activeProjectId = lastActiveProjectId
        val activeBranch = lastActiveBranch
        val sortedStats = lastStats.sortedWith { a, b ->
            val aUnassigned = a.id == unassignedProjectId
            val bUnassigned = b.id == unassignedProjectId
            if (aUnassigned != bUnassigned) {
                return@sortedWith if (aUnassigned) 1 else -1
            }

            // Active project first
            val aActive = a.id == activeProjectId
            val bActive = b.id == activeProjectId
            if (aActive != bActive) {
                return@sortedWith if (aActive) -1 else 1
            }

            val byToday = b.todayTime.compareTo(a.todayTime)
            if (byToday != 0) return@sortedWith byToday

            a.name.compareTo(b.name)
        }

        for (stat in sortedStats) {
            val isActiveProject = stat.id == activeProjectId

            // Get branches for this project
            val branches = state.getBranchesStats(stat.id)
            val hasBranches = branches.isNotEmpty()
            val isExpanded = expandedProjectIds.contains(stat.id)

            result.add(
                DisplayRow.ProjectHeader(
                    id = stat.id,
                    name = stat.name,
                    todayTime = stat.todayTime,
                    totalTime = stat.totalTime,
                    isActive = isActiveProject,
                    hasBranches = hasBranches,
                    isExpanded = isExpanded
                )
            )

            if (hasBranches && isExpanded) {
                // Sort branches: active first, then by total time
                val sortedBranches = branches.sortedWith { a, b ->
                    val aActive = isActiveProject && a.branch == activeBranch
                    val bActive = isActiveProject && b.branch == activeBranch
                    if (aActive != bActive) {
                        return@sortedWith if (aActive) -1 else 1
                    }
                    b.totalTime.compareTo(a.totalTime)
                }

                for (branchStat in sortedBranches) {
                    result.add(
                        DisplayRow.BranchRow(
                            projectId = stat.id,
                            branch = branchStat.branch,
                            todayTime = branchStat.todayTime,
                            totalTime = branchStat.totalTime,
                            isActiveBranch = isActiveProject && branchStat.branch == activeBranch
                        )
                    )
                }
            }
        }

        rows = result
        fireTableDataChanged()
    }

    fun isHeaderRow(rowIndex: Int): Boolean {
        return rows.getOrNull(rowIndex) is DisplayRow.ProjectHeader
    }

    fun isExpandableHeaderRow(rowIndex: Int): Boolean {
        return when (val row = rows.getOrNull(rowIndex)) {
            is DisplayRow.ProjectHeader -> row.hasBranches
            else -> false
        }
    }

    fun toggleExpanded(rowIndex: Int) {
        val row = rows.getOrNull(rowIndex) as? DisplayRow.ProjectHeader ?: return
        if (!row.hasBranches) return
        if (expandedProjectIds.contains(row.id)) {
            expandedProjectIds.remove(row.id)
        } else {
            expandedProjectIds.add(row.id)
        }
        rebuildRows()
    }

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = 3

    override fun getColumnName(column: Int): String = when (column) {
        0 -> "Project / Branch"
        1 -> "Today"
        2 -> "Total"
        else -> ""
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        return when (val row = rows[rowIndex]) {
            is DisplayRow.ProjectHeader -> when (columnIndex) {
                0 -> {
                    val marker = if (row.isActive) "● " else ""
                    val disclosure = if (!row.hasBranches) {
                        "  "
                    } else if (row.isExpanded) {
                        "▾ "
                    } else {
                        "▸ "
                    }
                    "$disclosure$marker${row.name}"
                }
                1 -> formatDuration(row.todayTime)
                2 -> formatDuration(row.totalTime)
                else -> ""
            }
            is DisplayRow.BranchRow -> when (columnIndex) {
                0 -> {
                    val prefix = if (row.isActiveBranch) "    ● " else "      "
                    "${prefix}↳ ${row.branch}"
                }
                1 -> formatDuration(row.todayTime)
                2 -> formatDuration(row.totalTime)
                else -> ""
            }
        }
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

class PeriodChartPanel : JPanel() {

    private var data: Map<String, Long> = emptyMap()
    private var periodDays: Int = 7

    init {
        preferredSize = Dimension(400, 180)
        maximumSize = Dimension(Int.MAX_VALUE, 180)
        isOpaque = false
    }

    fun updateData(newData: Map<String, Long>, days: Int) {
        data = newData
        periodDays = days
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        if (data.isEmpty()) return

        val padding = 20
        val bottomPadding = 25
        val topPadding = 20
        val chartHeight = height - bottomPadding - topPadding

        val sortedData = data.toSortedMap()
        val maxValue = data.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        val today = LocalDate.now()

        // Calculate bar width based on number of days
        val totalWidth = width - padding * 2
        val gap = if (periodDays <= 7) 8 else if (periodDays <= 14) 4 else 2
        val barWidth = (totalWidth - gap * (periodDays - 1)) / periodDays

        var x = padding

        sortedData.forEach { (dateStr, millis) ->
            val date = LocalDate.parse(dateStr)
            val barHeight = ((millis.toDouble() / maxValue) * chartHeight).toInt()
            val y = height - bottomPadding - barHeight

            // Bar
            val isToday = date == today
            g2.color = if (isToday) {
                JBColor(Color(66, 133, 244), Color(100, 150, 255))
            } else {
                JBColor(Color(180, 180, 200), Color(100, 100, 120))
            }
            g2.fillRoundRect(x, y, barWidth.coerceAtLeast(2), barHeight.coerceAtLeast(2), 4, 4)

            // Day label - show for smaller periods or every few days for larger
            val showLabel = when {
                periodDays <= 7 -> true
                periodDays <= 14 -> true
                else -> date.dayOfMonth == 1 || date.dayOfMonth == 15 || isToday
            }

            if (showLabel && barWidth > 10) {
                g2.color = JBColor.foreground()
                g2.font = g2.font.deriveFont(if (periodDays <= 14) 10f else 9f)

                val label = when {
                    periodDays <= 7 -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    periodDays <= 14 -> "${date.dayOfMonth}"
                    else -> "${date.dayOfMonth}/${date.monthValue}"
                }
                val labelWidth = g2.fontMetrics.stringWidth(label)
                g2.drawString(label, x + (barWidth - labelWidth) / 2, height - 5)
            }

            // Time label on top - only for bars with data and enough space
            if (millis > 0 && barWidth > 15) {
                g2.color = JBColor.gray
                g2.font = g2.font.deriveFont(9f)
                val timeStr = formatShortDuration(millis)
                val timeWidth = g2.fontMetrics.stringWidth(timeStr)
                if (barWidth >= timeWidth) {
                    g2.drawString(timeStr, x + (barWidth - timeWidth) / 2, y - 4)
                }
            }

            x += barWidth + gap
        }
    }

    private fun formatShortDuration(millis: Long): String {
        val minutes = millis / 60000
        val hours = minutes / 60
        return if (hours > 0) "${hours}h" else "${minutes}m"
    }
}
