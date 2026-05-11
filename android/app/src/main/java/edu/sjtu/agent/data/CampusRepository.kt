package edu.sjtu.agent.data

interface CampusRepository {
    suspend fun status(): SetupStatus
    suspend fun refreshDeadlines(): AgentResult<List<DeadlineItem>>
    suspend fun fetchSchedule(): AgentResult<List<ScheduleCourse>>
    suspend fun queryGrades(year: String = "", semester: String = ""): AgentResult<List<GradeItem>>
    suspend fun fetchNextLab(): AgentResult<LabBooking?>
    suspend fun searchCampus(query: String, sites: Set<String> = setOf("jwc", "shuiyuan", "dyweb")): AgentResult<List<CampusSearchItem>>
    suspend fun addReminder(title: String, startAt: String, endAt: String = "", note: String = ""): AgentResult<Reminder>
    suspend fun listReminders(): AgentResult<List<Reminder>>
    suspend fun removeReminder(id: Long): AgentResult<Unit>
}
