package com.example.routing

import com.example.domain.Agent
import com.example.domain.Assignment
import com.example.domain.Lead
import com.example.repository.AgentRepository
import com.example.repository.AssignmentRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val MAX_LEADS_PER_AGENT_PER_WINDOW = 5
private val CAPACITY_WINDOW: Duration = Duration.ofHours(24)

class NoEligibleAgentException(city: String) : Exception("No agent with available capacity in city '$city'")

class LeadRoutingService(
    private val agentRepository: AgentRepository,
    private val assignmentRepository: AssignmentRepository
) {
    // One lock per agent: serializes "count + decide + save" only for that agent,
    // so agents in different cities (or the same city) never wait on each other.
    private val agentLocks = ConcurrentHashMap<String, Mutex>()

    // Round-robin cursor per city, used to break ties between equally-loaded agents.
    private val roundRobinCursors = ConcurrentHashMap<String, AtomicInteger>()

    suspend fun assign(lead: Lead): Assignment {
        val since = Instant.now().minus(CAPACITY_WINDOW)
        val candidates = agentRepository.findByCity(lead.city)
        if (candidates.isEmpty()) throw NoEligibleAgentException(lead.city)

        val orderedCandidates = orderByLoadThenRoundRobin(candidates, lead.city, since)

        for (agent in orderedCandidates) {
            val lock = agentLocks.computeIfAbsent(agent.id) { Mutex() }
            val assignment = lock.withLock {
                val currentLoad = assignmentRepository.countForAgentSince(agent.id, since)
                if (currentLoad >= MAX_LEADS_PER_AGENT_PER_WINDOW) {
                    null
                } else {
                    assignmentRepository.save(Assignment(leadId = lead.id, agentId = agent.id))
                }
            }
            if (assignment != null) return assignment
            // agent was full (or lost a race to another lead) -> try next candidate
        }

        throw NoEligibleAgentException(lead.city)
    }

    private fun orderByLoadThenRoundRobin(candidates: List<Agent>, city: String, since: Instant): List<Agent> {
        val loadByAgent = candidates.associateWith { assignmentRepository.countForAgentSince(it.id, since) }
        val minLoad = loadByAgent.values.min()
        val leastLoaded = candidates.filter { loadByAgent.getValue(it) == minLoad }

        if (leastLoaded.size == 1) return leastLoaded + (candidates - leastLoaded.toSet())

        val cursor = roundRobinCursors.computeIfAbsent(city) { AtomicInteger(0) }
        val startIndex = cursor.getAndIncrement().mod(leastLoaded.size)
        val rotated = leastLoaded.subList(startIndex, leastLoaded.size) + leastLoaded.subList(0, startIndex)
        return rotated + (candidates - leastLoaded.toSet())
    }
}
