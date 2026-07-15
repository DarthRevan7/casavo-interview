package com.example

import com.example.domain.Lead
import com.example.domain.LeadRequest
import com.example.routing.LeadRoutingService
import com.example.routing.NoEligibleAgentException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class EchoRequest(val message: String)

@Serializable
data class EchoResponse(val message: String, val length: Int)

@Serializable
data class AssignmentResponse(
    val assignmentId: String,
    val leadId: String,
    val agentId: String
)

fun Application.configureRouting(leadRoutingService: LeadRoutingService) {
    routing {
        get("/") {
            call.respondText("Hello, World!")
        }
        get("/json/kotlinx-serialization") {
            call.respond(mapOf("hello" to "world"))
        }
        post("/v1/echo") {
            val request = call.receive<EchoRequest>()
            call.respond(HttpStatusCode.Created, EchoResponse(request.message, request.message.length))
        }
        post("/v1/leads") {
            val request = call.receive<LeadRequest>()
            val lead = Lead(
                customerName = request.customerName,
                customerEmail = request.customerEmail,
                customerPhone = request.customerPhone,
                propertyId = request.propertyId,
                city = request.city
            )
            try {
                val assignment = leadRoutingService.assign(lead)
                call.respond(
                    HttpStatusCode.Created,
                    AssignmentResponse(assignment.id, assignment.leadId, assignment.agentId)
                )
            } catch (e: NoEligibleAgentException) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message))
            }
        }
    }
}
