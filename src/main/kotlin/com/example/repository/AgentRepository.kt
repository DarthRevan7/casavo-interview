package com.example.repository

import com.example.domain.Agent

interface AgentRepository {
    fun findByCity(city: String): List<Agent>
    fun findById(id: String): Agent?
}

class InMemoryAgentRepository(agents: List<Agent>) : AgentRepository {
    private val agentsById = agents.associateBy { it.id }

    override fun findByCity(city: String): List<Agent> =
        agentsById.values.filter { it.city.equals(city, ignoreCase = true) }

    override fun findById(id: String): Agent? = agentsById[id]
}
