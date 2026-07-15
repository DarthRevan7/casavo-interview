package com.example.repository

import com.example.domain.Assignment
import com.example.domain.NotificationStatus
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface AssignmentRepository {
    fun save(assignment: Assignment): Assignment
    fun countForAgentSince(agentId: String, since: Instant): Int
    fun updateNotificationStatus(assignmentId: String, status: NotificationStatus)
    fun findPendingNotifications(): List<Assignment>
}

class InMemoryAssignmentRepository : AssignmentRepository {
    private val assignments = ConcurrentHashMap<String, Assignment>()

    override fun save(assignment: Assignment): Assignment {
        assignments[assignment.id] = assignment
        return assignment
    }

    override fun countForAgentSince(agentId: String, since: Instant): Int =
        assignments.values.count { it.agentId == agentId && it.assignedAt.isAfter(since) }

    override fun updateNotificationStatus(assignmentId: String, status: NotificationStatus) {
        assignments.computeIfPresent(assignmentId) { _, current -> current.copy(notificationStatus = status) }
    }

    override fun findPendingNotifications(): List<Assignment> =
        assignments.values.filter { it.notificationStatus == NotificationStatus.PENDING_NOTIFICATION }
}
