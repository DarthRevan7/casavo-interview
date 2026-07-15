package com.example.routing

import com.example.domain.Agent
import com.example.domain.Lead
import com.example.repository.InMemoryAgentRepository
import com.example.repository.InMemoryAssignmentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class LeadRoutingServiceConcurrencyTest {

    @Test
    fun `never assigns more than the capacity limit to a single agent under concurrent load`() = runBlocking {
        val agent = Agent(id = "agent-1", name = "Alice", city = "Milano")
        val assignmentRepository = InMemoryAssignmentRepository()
        val service = LeadRoutingService(
            agentRepository = InMemoryAgentRepository(listOf(agent)),
            assignmentRepository = assignmentRepository
        )

        val concurrentLeads = 50
        val successes = withContext(Dispatchers.Default) {
            (1..concurrentLeads).map {
                async {
                    val lead = Lead(
                        customerName = "Customer $it",
                        customerEmail = "customer$it@test.com",
                        customerPhone = "000$it",
                        propertyId = "prop-$it",
                        city = "Milano"
                    )
                    runCatching { service.assign(lead) }.isSuccess
                }
            }.awaitAll()
        }

        // exactly 5 of the 50 concurrent requests should have won a slot; the rest
        // must fail with NoEligibleAgentException instead of overbooking the agent
        assertEquals(5, successes.count { it })
        assertEquals(5, assignmentRepository.countForAgentSince(agent.id, java.time.Instant.now().minusSeconds(60)))
    }
}
