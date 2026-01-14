package com.focustracker.state

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service(Service.Level.APP)
@State(
    name = "FocusTimeState",
    storages = [Storage("focusTimeTracker.xml")]
)
class FocusTimeState : PersistentStateComponent<FocusTimeState> {

    // Map of date string (yyyy-MM-dd) to milliseconds focused (total across all projects)
    var dailyFocusTime: MutableMap<String, Long> = mutableMapOf()

    // Map of project id to (date -> milliseconds)
    var projectFocusTime: MutableMap<String, MutableMap<String, Long>> = mutableMapOf()

    // Tracking checkpoint start time (millis since epoch), null if not focused or paused
    var sessionStartTime: Long? = null

    // Current focused session start time (for UI), null if not focused or paused
    var focusSessionStartTime: Long? = null

    // Currently active project id
    var activeProject: String? = null

    // Map of project id to display name (best-effort)
    var projectDisplayNames: MutableMap<String, String> = mutableMapOf()

    // Manual pause state
    var isPaused: Boolean = false

    // Session start date for reset detection
    var sessionDate: String? = null

    override fun getState(): FocusTimeState = this

    override fun loadState(state: FocusTimeState) {
        XmlSerializerUtil.copyBean(state, this)
        migrateLegacyProjectKeys()
    }

    fun getTodayKey(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun getProjectDisplayName(projectId: String): String {
        projectDisplayNames[projectId]?.let { return it }
        return when {
            projectId.startsWith(PROJECT_ID_NAME_PREFIX) -> projectId.removePrefix(PROJECT_ID_NAME_PREFIX)
            projectId.startsWith(PROJECT_ID_LOCATION_PREFIX) -> projectId.removePrefix(PROJECT_ID_LOCATION_PREFIX)
            else -> projectId
        }
    }

    private fun isProjectId(value: String): Boolean =
        value.startsWith(PROJECT_ID_NAME_PREFIX) || value.startsWith(PROJECT_ID_LOCATION_PREFIX)

    private fun migrateLegacyProjectKeys() {
        activeProject?.let { current ->
            if (!isProjectId(current)) {
                val migrated = PROJECT_ID_NAME_PREFIX + current
                activeProject = migrated
                projectDisplayNames.putIfAbsent(migrated, current)
            }
        }

        val legacyKeys = projectFocusTime.keys.filter { !isProjectId(it) }
        if (legacyKeys.isEmpty()) return

        for (legacyKey in legacyKeys) {
            val migratedKey = PROJECT_ID_NAME_PREFIX + legacyKey
            val legacyData = projectFocusTime.remove(legacyKey) ?: continue

            val target = projectFocusTime.getOrPut(migratedKey) { mutableMapOf() }
            for ((date, millis) in legacyData) {
                target[date] = (target[date] ?: 0L) + millis
            }

            projectDisplayNames.putIfAbsent(migratedKey, legacyKey)
        }

        // Fill display names for already-migrated ids when missing.
        for (projectId in projectFocusTime.keys) {
            if (!projectDisplayNames.containsKey(projectId)) {
                projectDisplayNames[projectId] = getProjectDisplayName(projectId)
            }
        }
    }

    fun getTodayFocusTime(): Long {
        return synchronized(this) {
            val today = getTodayKey()
            val stored = dailyFocusTime[today] ?: 0L
            stored + getActiveTrackingOverlapForDay(today)
        }
    }

    fun getProjectTodayFocusTime(projectId: String): Long {
        return synchronized(this) {
            val today = getTodayKey()
            val stored = projectFocusTime[projectId]?.get(today) ?: 0L

            val activeTime = if (!isPaused && activeProject == projectId) {
                getActiveTrackingOverlapForDay(today)
            } else 0L

            stored + activeTime
        }
    }

    fun getPeriodFocusTime(days: Int): Map<String, Long> {
        return synchronized(this) {
            val result = mutableMapOf<String, Long>()
            val today = LocalDate.now()

            for (i in (days - 1) downTo 0) {
                val date = today.minusDays(i.toLong())
                val key = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                result[key] = dailyFocusTime[key] ?: 0L
            }

            // Add current active tracking time across all overlapping days in the period.
            val active = getActiveTrackingInterval()
            if (active != null) {
                for (key in result.keys) {
                    val overlap = overlapMillisWithDay(active.first, active.second, key)
                    if (overlap > 0L) {
                        result[key] = (result[key] ?: 0L) + overlap
                    }
                }
            }

            result
        }
    }

    fun getWeekFocusTime(): Map<String, Long> = getPeriodFocusTime(7)

    fun getMonthFocusTime(): Map<String, Long> = getPeriodFocusTime(30)

    fun getTotalFocusTime(): Long {
        return synchronized(this) {
            var total = dailyFocusTime.values.sum()
            getActiveTrackingInterval()?.let { (start, end) ->
                total += (end - start).coerceAtLeast(0L)
            }
            total
        }
    }

    fun getAllProjectsStats(): List<ProjectStatsRow> {
        return getAllProjectsStats(emptyList())
    }

    data class ProjectInfo(
        val id: String,
        val name: String
    )

    fun getAllProjectsStats(knownProjects: List<ProjectInfo>): List<ProjectStatsRow> {
        return synchronized(this) {
            migrateLegacyProjectKeys()
            migrateNameIdsToLocationIds(knownProjects)

            val today = getTodayKey()
            val rows = mutableListOf<ProjectStatsRow>()

            // Keep display names up to date for known projects.
            for (p in knownProjects) {
                projectDisplayNames[p.id] = p.name
            }

            val ids = LinkedHashSet<String>()
            ids.addAll(knownProjects.map { it.id })
            ids.addAll(projectFocusTime.keys)
            activeProject?.let(ids::add)
            ids.remove(UNASSIGNED_PROJECT_ID)

            for (projectId in ids) {
                val dateMap = projectFocusTime[projectId] ?: emptyMap()
                val todayTime = dateMap[today] ?: 0L
                val totalTime = dateMap.values.sum()

                val activeTodayTime = if (!isPaused && activeProject == projectId) {
                    getActiveTrackingOverlapForDay(today)
                } else 0L
                val activeTotalTime = if (!isPaused && activeProject == projectId) {
                    getActiveTrackingTotalMillis()
                } else 0L

                rows.add(
                    ProjectStatsRow(
                        id = projectId,
                        name = getProjectDisplayName(projectId),
                        todayTime = todayTime + activeTodayTime,
                        totalTime = totalTime + activeTotalTime
                    )
                )
            }

            // Any time tracked when we couldn't resolve a project ends up in dailyFocusTime only.
            val totalToday = getTodayFocusTime()
            val totalAllTime = getTotalFocusTime()
            val projectsToday = rows.sumOf { it.todayTime }
            val projectsAllTime = rows.sumOf { it.totalTime }

            val unassignedToday = (totalToday - projectsToday).coerceAtLeast(0L)
            val unassignedAllTime = (totalAllTime - projectsAllTime).coerceAtLeast(0L)
            if (unassignedToday > 0L || unassignedAllTime > 0L) {
                rows.add(
                    ProjectStatsRow(
                        id = UNASSIGNED_PROJECT_ID,
                        name = "(Unassigned)",
                        todayTime = unassignedToday,
                        totalTime = unassignedAllTime
                    )
                )
            }

            rows
        }
    }

    private fun migrateNameIdsToLocationIds(knownProjects: List<ProjectInfo>) {
        for (p in knownProjects) {
            if (!p.id.startsWith(PROJECT_ID_LOCATION_PREFIX)) continue

            val legacyKey = PROJECT_ID_NAME_PREFIX + p.name
            val legacyData = projectFocusTime[legacyKey] ?: continue

            val target = projectFocusTime.getOrPut(p.id) { mutableMapOf() }
            for ((date, millis) in legacyData) {
                target[date] = (target[date] ?: 0L) + millis
            }

            projectFocusTime.remove(legacyKey)
            projectDisplayNames[p.id] = p.name

            if (activeProject == legacyKey) {
                activeProject = p.id
            }
        }
    }

    data class ProjectStatsRow(
        val id: String,
        val name: String,
        val todayTime: Long,
        val totalTime: Long
    )

    private fun getActiveTrackingInterval(): Pair<Long, Long>? {
        if (isPaused) return null
        val start = sessionStartTime ?: return null
        val end = System.currentTimeMillis()
        return start to end
    }

    private fun getActiveTrackingTotalMillis(): Long {
        val interval = getActiveTrackingInterval() ?: return 0L
        return (interval.second - interval.first).coerceAtLeast(0L)
    }

    private fun getActiveTrackingOverlapForDay(dayKey: String): Long {
        val interval = getActiveTrackingInterval() ?: return 0L
        return overlapMillisWithDay(interval.first, interval.second, dayKey)
    }

    private fun overlapMillisWithDay(startMillis: Long, endMillis: Long, dayKey: String): Long {
        if (endMillis <= startMillis) return 0L
        val zone = ZoneId.systemDefault()
        val day = LocalDate.parse(dayKey, DateTimeFormatter.ISO_LOCAL_DATE)
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val overlapStart = maxOf(startMillis, dayStart)
        val overlapEnd = minOf(endMillis, dayEnd)
        return (overlapEnd - overlapStart).coerceAtLeast(0L)
    }

    companion object {
        private const val PROJECT_ID_NAME_PREFIX = "name:"
        private const val PROJECT_ID_LOCATION_PREFIX = "loc:"
        private const val UNASSIGNED_PROJECT_ID = "__unassigned__"

        fun getInstance(): FocusTimeState =
            ApplicationManager.getApplication().getService(FocusTimeState::class.java)
    }
}
