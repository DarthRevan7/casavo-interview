package com.example

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ServerTest {

    @Test
    fun `POST v1 leads assigns a lead to an eligible agent`() = testApplication {
        application {
            rootModule()
        }
        val response = client.post("/v1/leads") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"customerName":"Mario Rossi","customerEmail":"mario@test.com","customerPhone":"123","propertyId":"prop-1","city":"Milano"}"""
            )
        }
        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `POST v1 leads returns 409 when no agent is eligible for the city`() = testApplication {
        application {
            rootModule()
        }
        val response = client.post("/v1/leads") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"customerName":"Nessuno","customerEmail":"nobody@test.com","customerPhone":"000","propertyId":"prop-2","city":"Torino"}"""
            )
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

}
