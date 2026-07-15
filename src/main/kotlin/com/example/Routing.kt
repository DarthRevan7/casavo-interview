package com.example

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

fun Application.configureRouting() {
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
    }
}
