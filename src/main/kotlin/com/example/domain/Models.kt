package com.example.domain

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class LeadRequest(
    val customerName: String,
    val customerEmail: String,
    val customerPhone: String,
    val propertyId: String,
    val city: String
)

data class Lead(
    val id: String = UUID.randomUUID().toString(),
    val customerName: String,
    val customerEmail: String,
    val customerPhone: String,
    val propertyId: String,
    val city: String,
    val createdAt: Instant = Instant.now()
)

data class Agent(
    val id: String,
    val name: String,
    val city: String
)

enum class NotificationStatus {
    PENDING_NOTIFICATION,
    NOTIFIED,
    FAILED
}

data class Assignment(
    val id: String = UUID.randomUUID().toString(),
    val leadId: String,
    val agentId: String,
    val assignedAt: Instant = Instant.now(),
    val notificationStatus: NotificationStatus = NotificationStatus.PENDING_NOTIFICATION
)
