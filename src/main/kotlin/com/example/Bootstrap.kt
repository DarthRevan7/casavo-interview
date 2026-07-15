package com.example

import com.example.domain.Agent
import com.example.repository.InMemoryAgentRepository
import com.example.repository.InMemoryAssignmentRepository
import com.example.routing.LeadRoutingService

fun createLeadRoutingService(): LeadRoutingService {
    val agents = listOf(
        Agent(id = "agent-1", name = "Alice", city = "Milano"),
        Agent(id = "agent-2", name = "Bruno", city = "Milano"),
        Agent(id = "agent-3", name = "Carla", city = "Roma"),
        Agent(id = "agent-4", name = "Dario", city = "Roma")
    )
    return LeadRoutingService(
        agentRepository = InMemoryAgentRepository(agents),
        assignmentRepository = InMemoryAssignmentRepository()
    )
}
