package com.nano

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.event.*

data class CalendarCredentials(
    val username: String,
    val password: String
)

fun Application.configureSecurity() {
    authentication {
        basic(name = "calendar-auth") {
            realm = "Calendar Access"
            validate { credentials ->
                if (credentials.name.isNotEmpty() && credentials.password.isNotEmpty()) {
                    UserIdPrincipal(credentials.name)
                } else {
                    null
                }
            }
        }
    }
}
