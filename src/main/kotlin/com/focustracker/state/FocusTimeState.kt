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

    // AI/Terminal activity: total across all projects (date -> milliseconds)
    var aiDailyTime: MutableMap<String, Long> = mutableMapOf()

    // AI/Terminal activity: project id -> (date -> milliseconds)
    var aiProjectTime: MutableMap<String, MutableMap<String, Long>> = mutableMapOf()

    // AI/Terminal "active" segments per project (start -> end), where end = lastActivity + idleThreshold
    var aiActiveSegments: MutableMap<String, AiSegment> = mutableMapOf()

    // Tracking checkpoint start time (millis since epoch), null if not focused or paused
    var sessionStartTime: Long? = null

    // Current focused session start time (for UI), null if not focused or paused
    var focusSessionStartTime: Long? = null

    // Currently active project id
    var activeProject: String? = null

    // Map of project id to display name (best-effort)
    var projectDisplayNames: MutableMap<String, String> = mutableMapOf()

    // Map of project id to project path (best-effort, e.g. basePath)
    var projectPaths: MutableMap<String, String> = mutableMapOf()

    // Manual pause state
    var isPaused: Boolean = false

    // Feature flag: terminal AI activity tracking
    var isAiTrackingEnabled: Boolean = false

    // Session start date for reset detection
    var sessionDate: String? = null

    override fun getState(): FocusTimeState = this

    override fun loadState(state: FocusTimeState) {
        XmlSerializerUtil.copyBean(state, this)
        migrateLegacyProjectKeys()
        flushExpiredAiSegments(System.currentTimeMillis())
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

    fun getProjectPath(projectId: String): String? {
        return projectPaths[projectId]
    }

    /**
     * Returns display name with parent directory prefix for disambiguation.
     * Example: "work/MyApp" instead of just "MyApp"
     */
    fun getProjectDisplayNameWithParent(projectId: String): String {
        val name = getProjectDisplayName(projectId)
        val path = projectPaths[projectId] ?: return name

        val parentName = java.io.File(path).parentFile?.name
        return if (parentName != null && parentName.isNotBlank()) {
            "$parentName/$name"
        } else {
            name
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
        val legacyAiKeys = aiProjectTime.keys.filter { !isProjectId(it) }
        if (legacyKeys.isEmpty() && legacyAiKeys.isEmpty()) return

        for (legacyKey in legacyKeys) {
            val migratedKey = PROJECT_ID_NAME_PREFIX + legacyKey
            val legacyData = projectFocusTime.remove(legacyKey) ?: continue

            val target = projectFocusTime.getOrPut(migratedKey) { mutableMapOf() }
            for ((date, millis) in legacyData) {
                target[date] = (target[date] ?: 0L) + millis
            }

            projectDisplayNames.putIfAbsent(migratedKey, legacyKey)
            projectPaths[legacyKey]?.let { legacyPath ->
                projectPaths.remove(legacyKey)
                projectPaths.putIfAbsent(migratedKey, legacyPath)
            }
        }

        for (legacyKey in legacyAiKeys) {
            val migratedKey = PROJECT_ID_NAME_PREFIX + legacyKey
            val legacyData = aiProjectTime.remove(legacyKey) ?: continue

            val target = aiProjectTime.getOrPut(migratedKey) { mutableMapOf() }
            for ((date, millis) in legacyData) {
                target[date] = (target[date] ?: 0L) + millis
            }

            projectDisplayNames.putIfAbsent(migratedKey, legacyKey)
            projectPaths[legacyKey]?.let { legacyPath ->
                projectPaths.remove(legacyKey)
                projectPaths.putIfAbsent(migratedKey, legacyPath)
            }
        }

        // Fill display names for already-migrated ids when missing.
        for (projectId in projectFocusTime.keys + aiProjectTime.keys) {
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

    fun recordAiActivity(projectId: String, projectName: String? = null, nowMillis: Long = System.currentTimeMillis()) {
        synchronized(this) {
            projectName?.let { projectDisplayNames[projectId] = it }

            val segment = aiActiveSegments[projectId]
            val newEnd = nowMillis + AI_IDLE_THRESHOLD_MILLIS

            if (segment == null) {
                aiActiveSegments[projectId] = AiSegment(startMillis = nowMillis, endMillis = newEnd)
                return
            }

            if (nowMillis <= segment.endMillis) {
                segment.endMillis = maxOf(segment.endMillis, newEnd)
                return
            }

            // Previous segment ended; persist it and start a new one.
            persistAiInterval(projectId, segment.startMillis, segment.endMillis)
            segment.startMillis = nowMillis
            segment.endMillis = newEnd
        }
    }

    fun flushExpiredAiSegments(nowMillis: Long = System.currentTimeMillis()) {
        synchronized(this) {
            val it = aiActiveSegments.entries.iterator()
            while (it.hasNext()) {
                val (projectId, segment) = it.next()
                if (segment.endMillis <= nowMillis) {
                    persistAiInterval(projectId, segment.startMillis, segment.endMillis)
                    it.remove()
                }
            }
        }
    }

    fun endAiSegmentsAt(cutoffMillis: Long) {
        synchronized(this) {
            val it = aiActiveSegments.entries.iterator()
            while (it.hasNext()) {
                val (projectId, segment) = it.next()
                val end = minOf(segment.endMillis, cutoffMillis)
                persistAiInterval(projectId, segment.startMillis, end)
                it.remove()
            }
        }
    }

    fun getAiTodayTime(): Long {
        return synchronized(this) {
            val todayKey = getTodayKey()
            val stored = aiDailyTime[todayKey] ?: 0L
            stored + getAiActiveOverlapForDay(todayKey)
        }
    }

    fun getAiTotalTime(): Long {
        return synchronized(this) {
            var total = aiDailyTime.values.sum()
            val now = System.currentTimeMillis()
            for (segment in aiActiveSegments.values) {
                val end = minOf(now, segment.endMillis)
                total += (end - segment.startMillis).coerceAtLeast(0L)
            }
            total
        }
    }

    fun getAiPeriodTime(days: Int): Map<String, Long> {
        return synchronized(this) {
            val result = mutableMapOf<String, Long>()
            val today = LocalDate.now()

            for (i in (days - 1) downTo 0) {
                val date = today.minusDays(i.toLong())
                val key = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                result[key] = aiDailyTime[key] ?: 0L
            }

            val now = System.currentTimeMillis()
            for ((_, segment) in aiActiveSegments) {
                val start = segment.startMillis
                val end = minOf(now, segment.endMillis)
                if (end <= start) continue
                for (key in result.keys) {
                    val overlap = overlapMillisWithDay(start, end, key)
                    if (overlap > 0L) {
                        result[key] = (result[key] ?: 0L) + overlap
                    }
                }
            }

            result
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
        val name: String,
        val path: String? = null
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
                        name = getProjectDisplayNameWithParent(projectId),
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

    fun getAiProjectsStats(knownProjects: List<ProjectInfo>): List<ProjectStatsRow> {
        return synchronized(this) {
            migrateLegacyProjectKeys()
            migrateNameIdsToLocationIds(knownProjects)

            // Keep display names up to date for known projects.
            for (p in knownProjects) {
                projectDisplayNames[p.id] = p.name
            }

            val today = getTodayKey()
            val now = System.currentTimeMillis()

            val ids = LinkedHashSet<String>()
            ids.addAll(knownProjects.map { it.id })
            ids.addAll(aiProjectTime.keys)
            ids.addAll(aiActiveSegments.keys)

            ids.map { projectId ->
                val dateMap = aiProjectTime[projectId] ?: emptyMap()
                val storedToday = dateMap[today] ?: 0L
                val storedTotal = dateMap.values.sum()

                val segment = aiActiveSegments[projectId]
                val activeEnd = segment?.let { minOf(now, it.endMillis) } ?: 0L
                val activeToday = if (segment != null) overlapMillisWithDay(segment.startMillis, activeEnd, today) else 0L
                val activeTotal = if (segment != null) (activeEnd - segment.startMillis).coerceAtLeast(0L) else 0L

                ProjectStatsRow(
                    id = projectId,
                    name = getProjectDisplayNameWithParent(projectId),
                    todayTime = storedToday + activeToday,
                    totalTime = storedTotal + activeTotal
                )
            }
        }
    }

    private fun migrateNameIdsToLocationIds(knownProjects: List<ProjectInfo>) {
        for (p in knownProjects) {
            if (!p.id.startsWith(PROJECT_ID_LOCATION_PREFIX)) continue

            val legacyKey = PROJECT_ID_NAME_PREFIX + p.name

            projectFocusTime[legacyKey]?.let { legacyData ->
                val target = projectFocusTime.getOrPut(p.id) { mutableMapOf() }
                for ((date, millis) in legacyData) {
                    target[date] = (target[date] ?: 0L) + millis
                }
                projectFocusTime.remove(legacyKey)
                if (activeProject == legacyKey) {
                    activeProject = p.id
                }
            }

            aiProjectTime[legacyKey]?.let { legacyData ->
                val target = aiProjectTime.getOrPut(p.id) { mutableMapOf() }
                for ((date, millis) in legacyData) {
                    target[date] = (target[date] ?: 0L) + millis
                }
                aiProjectTime.remove(legacyKey)
            }

            projectPaths[legacyKey]?.let { legacyPath ->
                projectPaths.remove(legacyKey)
                projectPaths[p.id] = legacyPath
            }

            projectDisplayNames[p.id] = p.name
        }
    }

    fun getTodayFocusTimeForProjects(projectIds: Set<String>): Long {
        if (projectIds.isEmpty()) return 0L
        return synchronized(this) {
            val today = getTodayKey()
            var total = 0L
            for (id in projectIds) {
                total += projectFocusTime[id]?.get(today) ?: 0L
            }
            if (!isPaused && activeProject != null && projectIds.contains(activeProject)) {
                total += getActiveTrackingOverlapForDay(today)
            }
            total
        }
    }

    fun getTotalFocusTimeForProjects(projectIds: Set<String>): Long {
        if (projectIds.isEmpty()) return 0L
        return synchronized(this) {
            var total = 0L
            for (id in projectIds) {
                total += projectFocusTime[id]?.values?.sum() ?: 0L
            }
            if (!isPaused && activeProject != null && projectIds.contains(activeProject)) {
                total += getActiveTrackingTotalMillis()
            }
            total
        }
    }

    fun getPeriodFocusTimeForProjects(days: Int, projectIds: Set<String>): Map<String, Long> {
        if (projectIds.isEmpty()) return emptyMap()
        return synchronized(this) {
            val result = mutableMapOf<String, Long>()
            val today = LocalDate.now()

            for (i in (days - 1) downTo 0) {
                val date = today.minusDays(i.toLong())
                val key = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                result[key] = 0L
            }

            for (id in projectIds) {
                val dateMap = projectFocusTime[id] ?: continue
                for ((day, millis) in dateMap) {
                    if (result.containsKey(day)) {
                        result[day] = (result[day] ?: 0L) + millis
                    }
                }
            }

            if (!isPaused && activeProject != null && projectIds.contains(activeProject)) {
                val active = getActiveTrackingInterval()
                if (active != null) {
                    for (key in result.keys) {
                        val overlap = overlapMillisWithDay(active.first, active.second, key)
                        if (overlap > 0L) {
                            result[key] = (result[key] ?: 0L) + overlap
                        }
                    }
                }
            }

            result
        }
    }

    fun getAiTodayTimeForProjects(projectIds: Set<String>): Long {
        if (projectIds.isEmpty()) return 0L
        return synchronized(this) {
            val todayKey = getTodayKey()
            val now = System.currentTimeMillis()
            var total = 0L
            for (id in projectIds) {
                total += aiProjectTime[id]?.get(todayKey) ?: 0L
                val segment = aiActiveSegments[id]
                if (segment != null) {
                    val end = minOf(now, segment.endMillis)
                    total += overlapMillisWithDay(segment.startMillis, end, todayKey)
                }
            }
            total
        }
    }

    fun getAiTotalTimeForProjects(projectIds: Set<String>): Long {
        if (projectIds.isEmpty()) return 0L
        return synchronized(this) {
            var total = 0L
            val now = System.currentTimeMillis()
            for (id in projectIds) {
                total += aiProjectTime[id]?.values?.sum() ?: 0L
                val segment = aiActiveSegments[id]
                if (segment != null) {
                    val end = minOf(now, segment.endMillis)
                    total += (end - segment.startMillis).coerceAtLeast(0L)
                }
            }
            total
        }
    }

    fun getAiPeriodTimeForProjects(days: Int, projectIds: Set<String>): Map<String, Long> {
        if (projectIds.isEmpty()) return emptyMap()
        return synchronized(this) {
            val result = mutableMapOf<String, Long>()
            val today = LocalDate.now()

            for (i in (days - 1) downTo 0) {
                val date = today.minusDays(i.toLong())
                val key = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                result[key] = 0L
            }

            for (id in projectIds) {
                val dateMap = aiProjectTime[id] ?: continue
                for ((day, millis) in dateMap) {
                    if (result.containsKey(day)) {
                        result[day] = (result[day] ?: 0L) + millis
                    }
                }
            }

            val now = System.currentTimeMillis()
            for (id in projectIds) {
                val segment = aiActiveSegments[id] ?: continue
                val start = segment.startMillis
                val end = minOf(now, segment.endMillis)
                if (end <= start) continue
                for (key in result.keys) {
                    val overlap = overlapMillisWithDay(start, end, key)
                    if (overlap > 0L) {
                        result[key] = (result[key] ?: 0L) + overlap
                    }
                }
            }

            result
        }
    }

    private fun getAiActiveOverlapForDay(dayKey: String): Long {
        val now = System.currentTimeMillis()
        var total = 0L
        for (segment in aiActiveSegments.values) {
            val end = minOf(now, segment.endMillis)
            total += overlapMillisWithDay(segment.startMillis, end, dayKey)
        }
        return total
    }

    private fun persistAiInterval(projectId: String, startMillis: Long, endMillis: Long) {
        if (endMillis <= startMillis) return
        val zone = ZoneId.systemDefault()

        var cursor = startMillis
        var date = java.time.Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
        while (cursor < endMillis) {
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val segmentEnd = minOf(endMillis, nextMidnight)
            val elapsed = (segmentEnd - cursor).coerceAtLeast(0L)
            val dateKey = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

            aiDailyTime[dateKey] = (aiDailyTime[dateKey] ?: 0L) + elapsed
            val projectData = aiProjectTime.getOrPut(projectId) { mutableMapOf() }
            projectData[dateKey] = (projectData[dateKey] ?: 0L) + elapsed

            cursor = segmentEnd
            date = date.plusDays(1)
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
        private const val AI_IDLE_THRESHOLD_MILLIS = 10 * 1000L

        fun getInstance(): FocusTimeState =
            ApplicationManager.getApplication().getService(FocusTimeState::class.java)
    }

    data class AiSegment(
        var startMillis: Long,
        var endMillis: Long
    )
}
