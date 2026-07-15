package com.example.notification

import com.example.domain.Assignment
import kotlin.random.Random

interface AgentNotifier {
    suspend fun notify(assignment: Assignment)
}

class UnreliableAgentNotifier(private val failureRate: Double = 0.4) : AgentNotifier {
    override suspend fun notify(assignment: Assignment) {
        if (Random.nextDouble() < failureRate) {
            throw NotificationDeliveryException(assignment.id)
        }
    }
}

class NotificationDeliveryException(assignmentId: String) :
    Exception("Failed to notify agent for assignment '$assignmentId'")
