package com.nano

import io.ktor.server.application.Application
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.routing.routing

fun Application.configureSwagger() {
    routing {
        routing {
            swaggerUI(path = "swagger", swaggerFile = "openapi.yaml")
        }
    }
}