package com.example.repository

import com.example.domain.Assignment
import com.example.domain.NotificationStatus
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface AssignmentRepository {
    fun save(assignment: Assignment): Assignment
    fun countForAgentSince(agentId: String, since: Instant): Int
    fun markNotified(assignmentId: String)
    fun recordFailedAttempt(assignmentId: String, maxAttempts: Int)
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

    override fun markNotified(assignmentId: String) {
        assignments.computeIfPresent(assignmentId) { _, current ->
            current.copy(notificationStatus = NotificationStatus.NOTIFIED)
        }
    }

    override fun recordFailedAttempt(assignmentId: String, maxAttempts: Int) {
        assignments.computeIfPresent(assignmentId) { _, current ->
            val attempts = current.notificationAttempts + 1
            current.copy(
                notificationAttempts = attempts,
                notificationStatus = if (attempts >= maxAttempts) NotificationStatus.FAILED else current.notificationStatus
            )
        }
    }

    override fun findPendingNotifications(): List<Assignment> =
        assignments.values.filter { it.notificationStatus == NotificationStatus.PENDING_NOTIFICATION }
}
