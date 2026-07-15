package com.example

import io.ktor.server.application.Application

fun Application.rootModule() {
    val components = createAppComponents()
    configureSerialization()
    configureRouting(components.leadRoutingService)
    components.notificationOutboxWorker.start(this)
}
