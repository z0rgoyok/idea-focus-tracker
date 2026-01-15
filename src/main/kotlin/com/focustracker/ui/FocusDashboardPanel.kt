package com.focustracker.ui

import com.focustracker.services.FocusTrackingService
import com.focustracker.state.FocusTimeState
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.*
import javax.swing.*
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

    private val periodOptions = arrayOf("5 days", "7 days", "14 days", "30 days")
    private val periodDays = intArrayOf(5, 7, 14, 30)
    private val periodComboBox = com.intellij.openapi.ui.ComboBox(periodOptions)
    private var selectedPeriodDays = 7

    private val historyFilterOptions = arrayOf("All", "Mine", "AI")
    private val historyFilterComboBox = com.intellij.openapi.ui.ComboBox(historyFilterOptions)
    private var selectedHistoryFilter = HistoryFilter.ALL

    private val periodChartPanel = PeriodChartPanel()
    private val projectsTableModel = ProjectsTableModel()
    private val projectsTable = JBTable(projectsTableModel)
    private val aiProjectsTableModel = ProjectsTableModel()
    private val aiProjectsTable = JBTable(aiProjectsTableModel)

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
        historyFilterComboBox.selectedIndex = 0 // Default to All
        historyFilterComboBox.addActionListener {
            selectedHistoryFilter = HistoryFilter.entries[historyFilterComboBox.selectedIndex]
            refreshData()
        }

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Header with status and pause button
        contentPanel.add(createHeaderPanel())
        contentPanel.add(Box.createVerticalStrut(20))

        // Current stats cards
        contentPanel.add(createStatsCardsPanel())
        contentPanel.add(Box.createVerticalStrut(24))

        // Projects section
        contentPanel.add(createSectionLabel("Projects"))
        contentPanel.add(Box.createVerticalStrut(8))
        contentPanel.add(createProjectsPanel(projectsTable))
        contentPanel.add(Box.createVerticalStrut(16))
        contentPanel.add(createSectionLabel("AI"))
        contentPanel.add(Box.createVerticalStrut(8))
        contentPanel.add(createProjectsPanel(aiProjectsTable))
        contentPanel.add(Box.createVerticalStrut(24))

        // Period chart with selector
        contentPanel.add(createPeriodHeaderPanel())
        contentPanel.add(Box.createVerticalStrut(8))
        contentPanel.add(periodChartPanel)

        val scrollPane = JBScrollPane(contentPanel).apply {
            border = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(scrollPane, BorderLayout.CENTER)

        // Register for updates
        FocusTrackingService.getInstance().addListener(updateListener)
        Disposer.register(this, Disposable {
            FocusTrackingService.getInstance().removeListener(updateListener)
        })

        refreshData()
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

            // Custom renderer for time columns
            val timeRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable?, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    horizontalAlignment = SwingConstants.RIGHT
                    border = JBUI.Borders.empty(0, 8)
                    return this
                }
            }

            columnModel.getColumn(1).cellRenderer = timeRenderer
            columnModel.getColumn(2).cellRenderer = timeRenderer

            // Set column widths
            columnModel.getColumn(0).preferredWidth = 200
            columnModel.getColumn(1).preferredWidth = 100
            columnModel.getColumn(2).preferredWidth = 100
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 200)
            preferredSize = Dimension(400, 150)

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

        // Update status
        val isPaused = service.isPaused()
        val isFocused = service.isFocused()

        val statusText = when {
            isPaused -> "Paused"
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

        // Get period data
        val focusPeriodData = state.getPeriodFocusTime(selectedPeriodDays)
        val aiPeriodData = state.getAiPeriodTime(selectedPeriodDays)

        val periodData = when (selectedHistoryFilter) {
            HistoryFilter.ALL -> focusPeriodData.mapValues { (k, v) -> v + (aiPeriodData[k] ?: 0L) }
            HistoryFilter.MINE -> focusPeriodData
            HistoryFilter.AI -> aiPeriodData
        }

        val todayMillis = when (selectedHistoryFilter) {
            HistoryFilter.ALL -> state.getTodayFocusTime() + state.getAiTodayTime()
            HistoryFilter.MINE -> state.getTodayFocusTime()
            HistoryFilter.AI -> state.getAiTodayTime()
        }

        val periodMillis = periodData.values.sum()

        val allTimeMillis = when (selectedHistoryFilter) {
            HistoryFilter.ALL -> state.getTotalFocusTime() + state.getAiTotalTime()
            HistoryFilter.MINE -> state.getTotalFocusTime()
            HistoryFilter.AI -> state.getAiTotalTime()
        }

        // Update stats
        todayTimeLabel.text = formatDuration(todayMillis)
        sessionTimeLabel.text = formatDuration(service.getCurrentSessionTime())
        periodTotalLabel.text = formatDuration(periodMillis)
        allTimeTotalLabel.text = formatDuration(allTimeMillis)

        // Update projects table
        projectsTableModel.updateData(state.getAllProjectsStats(knownProjects), service.getActiveProjectId())
        aiProjectsTableModel.updateData(state.getAiProjectsStats(knownProjects), service.getActiveProjectId())

        // Update chart
        periodChartPanel.updateData(periodData, selectedPeriodDays)
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

    override fun dispose() {
        // Cleanup handled by Disposer
    }

    private enum class HistoryFilter {
        ALL,
        MINE,
        AI
    }
}

class ProjectsTableModel : AbstractTableModel() {

    private var projects: List<ProjectRow> = emptyList()
    private val unassignedProjectId = "__unassigned__"

    data class ProjectRow(
        val id: String,
        val name: String,
        val todayTime: Long,
        val totalTime: Long,
        val isActive: Boolean
    )

    fun updateData(stats: List<FocusTimeState.ProjectStatsRow>, activeProjectId: String?) {
        projects = stats.map { stat ->
            ProjectRow(
                id = stat.id,
                name = stat.name,
                todayTime = stat.todayTime,
                totalTime = stat.totalTime,
                isActive = stat.id == activeProjectId
            )
        }.sortedWith(
            compareBy<ProjectRow> { it.id == unassignedProjectId }
                .thenByDescending { it.todayTime }
        )

        fireTableDataChanged()
    }

    override fun getRowCount(): Int = projects.size

    override fun getColumnCount(): Int = 3

    override fun getColumnName(column: Int): String = when (column) {
        0 -> "Project"
        1 -> "Today"
        2 -> "Total"
        else -> ""
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = projects[rowIndex]
        return when (columnIndex) {
            0 -> if (row.isActive) "● ${row.name}" else row.name
            1 -> formatDuration(row.todayTime)
            2 -> formatDuration(row.totalTime)
            else -> ""
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
