package com.example

import com.example.domain.Agent
import com.example.notification.NotificationOutboxWorker
import com.example.notification.UnreliableAgentNotifier
import com.example.repository.AssignmentRepository
import com.example.repository.InMemoryAgentRepository
import com.example.repository.InMemoryAssignmentRepository
import com.example.routing.LeadRoutingService

class AppComponents(
    val leadRoutingService: LeadRoutingService,
    val notificationOutboxWorker: NotificationOutboxWorker
)

fun createAppComponents(): AppComponents {
    val agents = listOf(
        Agent(id = "agent-1", name = "Alice", city = "Milano"),
        Agent(id = "agent-2", name = "Bruno", city = "Milano"),
        Agent(id = "agent-3", name = "Carla", city = "Roma"),
        Agent(id = "agent-4", name = "Dario", city = "Roma")
    )
    val assignmentRepository: AssignmentRepository = InMemoryAssignmentRepository()

    return AppComponents(
        leadRoutingService = LeadRoutingService(
            agentRepository = InMemoryAgentRepository(agents),
            assignmentRepository = assignmentRepository
        ),
        notificationOutboxWorker = NotificationOutboxWorker(
            assignmentRepository = assignmentRepository,
            agentNotifier = UnreliableAgentNotifier()
        )
    )
}
