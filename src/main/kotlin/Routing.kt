package com.nano

import com.nano.Service.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.event.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Serializable
data class CreateEventRequest(
    val summary: String,
    val description: String? = null,
    val startDateTime: String, // ISO format: "2025-07-20T10:00:00"
    val endDateTime: String,
    val location: String? = null
)

@Serializable
data class EventResponse(
    val uid: String,
    val summary: String,
    val description: String? = null,
    val startDateTime: String,
    val endDateTime: String,
    val location: String? = null
)

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null
)

fun Application.configureRouting() {
    routing {
        authenticate("calendar-auth") {
            route("/calendar/{calendarId}") {
                route("/events") {

                    // Получение всех событий
                    get {
                        try {
                            val principal = call.principal<UserIdPrincipal>()!!
                            val username = principal.name

                            // Получаем пароль из заголовка Authorization для создания DAV подключения
                            val authHeader = call.request.headers["Authorization"]
                            val password = authHeader?.let { header ->
                                val encoded = header.substringAfter("Basic ")
                                val decoded = java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
                                decoded.substringAfter(":")
                            } ?: ""

                            // Получаем календарь ID из заголовка CAL_ID
                            val calendarId = call.parameters["calendarId"] ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                ApiResponse<String>(success = false, message = "Не найден параметр calendarId")
                            )

                            // Получаем параметры фильтрации по датам (если они указаны)
                            val startDateParam = call.request.queryParameters["startDate"]
                            val endDateParam = call.request.queryParameters["endDate"]

                            var startDate: LocalDateTime? = null
                            var endDate: LocalDateTime? = null

                            try {
                                if (startDateParam != null) {
                                    startDate = LocalDateTime.parse(startDateParam)
                                }
                                if (endDateParam != null) {
                                    endDate = LocalDateTime.parse(endDateParam)
                                }
                            } catch (e: Exception) {
                                return@get call.respond(
                                    HttpStatusCode.BadRequest,
                                    ApiResponse<String>(success = false, message = "Некорректный формат даты. Используйте формат ISO: YYYY-MM-DDTHH:mm:ss")
                                )
                            }

                            val dav = Dav(username, password, calendarId)
                            val eventManager = dav.getEventManager()

                            // Получаем события с учетом фильтрации по датам
                            val events = when {
                                startDate != null && endDate != null -> {
                                    eventManager.getEventsByDateRange(startDate, endDate)
                                }
                                else -> {
                                    eventManager.getAllEvents().getOrElse {
                                        return@get call.respond(
                                            HttpStatusCode.InternalServerError,
                                            ApiResponse<String>(success = false, message = it.message)
                                        )
                                    }
                                }
                            }

                            val eventResponses = events.map { event ->
                                EventResponse(
                                    uid = event.uid,
                                    summary = event.summary,
                                    description = event.description,
                                    startDateTime = event.startDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                    endDateTime = event.endDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                    location = event.location
                                )
                            }
                            call.respond(ApiResponse(success = true, data = eventResponses))
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ApiResponse<String>(success = false, message = "Неожиданная ошибка: ${e.message}")
                            )
                        }
                    }

                    // Создание нового события
                    post {
                        try {
                            val principal = call.principal<UserIdPrincipal>()!!
                            val username = principal.name

                            val authHeader = call.request.headers["Authorization"]
                            val password = authHeader?.let { header ->
                                val encoded = header.substringAfter("Basic ")
                                val decoded = java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
                                decoded.substringAfter(":")
                            } ?: ""

                            // Получаем календарь ID из заголовка CAL_ID
                            val calendarId = call.parameters["calendarId"] ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                ApiResponse<String>(success = false, message = "Не найден параметр calendarId")
                            )

                            val request = call.receive<CreateEventRequest>()

                            val event = CalendarEvent(
                                summary = request.summary,
                                description = request.description,
                                startDateTime = LocalDateTime.parse(request.startDateTime),
                                endDateTime = LocalDateTime.parse(request.endDateTime),
                                location = request.location
                            )

                            val dav = Dav(username, password, calendarId)
                            val eventManager = dav.getEventManager()

                            eventManager.addEvent(event).fold(
                                onSuccess = { createdEvent ->
                                    val response = EventResponse(
                                        uid = createdEvent.uid,
                                        summary = createdEvent.summary,
                                        description = createdEvent.description,
                                        startDateTime = createdEvent.startDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                        endDateTime = createdEvent.endDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                        location = createdEvent.location
                                    )
                                    call.respond(HttpStatusCode.Created, ApiResponse(success = true, data = response))
                                },
                                onFailure = { error ->
                                    val statusCode = when {
                                        error.message?.contains("авторизации") == true -> HttpStatusCode.Unauthorized
                                        error.message?.contains("запрещен") == true -> HttpStatusCode.Forbidden
                                        error.message?.contains("не найден") == true -> HttpStatusCode.NotFound
                                        error.message?.contains("Конфликт") == true -> HttpStatusCode.Conflict
                                        else -> HttpStatusCode.BadRequest
                                    }
                                    call.respond(statusCode, ApiResponse<String>(success = false, message = error.message))
                                }
                            )
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ApiResponse<String>(success = false, message = "Ошибка создания события: ${e.message}")
                            )
                        }
                    }

                    // Получение события по UID
                    get("/{uid}") {
                        try {
                            val principal = call.principal<UserIdPrincipal>()!!
                            val username = principal.name
                            val uid = call.parameters["uid"] ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                ApiResponse<String>(success = false, message = "UID события не указан")
                            )

                            val authHeader = call.request.headers["Authorization"]
                            val password = authHeader?.let { header ->
                                val encoded = header.substringAfter("Basic ")
                                val decoded = java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
                                decoded.substringAfter(":")
                            } ?: ""

                            // Получаем календарь ID из заголовка CAL_ID
                            val calendarId = call.parameters["calendarId"] ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                ApiResponse<String>(success = false, message = "Не найден параметр calendarId")
                            )

                            val dav = Dav(username, password, calendarId)
                            val eventManager = dav.getEventManager()

                            eventManager.getEvent(uid).fold(
                                onSuccess = { event ->
                                    if (event != null) {
                                        val response = EventResponse(
                                            uid = event.uid,
                                            summary = event.summary,
                                            description = event.description,
                                            startDateTime = event.startDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                            endDateTime = event.endDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                            location = event.location
                                        )
                                        call.respond(ApiResponse(success = true, data = response))
                                    } else {
                                        call.respond(
                                            HttpStatusCode.NotFound,
                                            ApiResponse<String>(success = false, message = "Событие не найдено")
                                        )
                                    }
                                },
                                onFailure = { error ->
                                    val statusCode = when {
                                        error.message?.contains("авторизации") == true -> HttpStatusCode.Unauthorized
                                        error.message?.contains("запрещен") == true -> HttpStatusCode.Forbidden
                                        error.message?.contains("не найдено") == true -> HttpStatusCode.NotFound
                                        else -> HttpStatusCode.InternalServerError
                                    }
                                    call.respond(statusCode, ApiResponse<String>(success = false, message = error.message))
                                }
                            )
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ApiResponse<String>(success = false, message = "Ошибка получения события: ${e.message}")
                            )
                        }
                    }

                    // Обновление события
                    put("/{uid}") {
                        try {
                            val principal = call.principal<UserIdPrincipal>()!!
                            val username = principal.name
                            val uid = call.parameters["uid"] ?: return@put call.respond(
                                HttpStatusCode.BadRequest,
                                ApiResponse<String>(success = false, message = "UID события не указан")
                            )

                            val authHeader = call.request.headers["Authorization"]
                            val password = authHeader?.let { header ->
                                val encoded = header.substringAfter("Basic ")
                                val decoded = java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
                                decoded.substringAfter(":")
                            } ?: ""

                            // Получаем календарь ID из заголовка CAL_ID
                            val calendarId = call.parameters["calendarId"] ?: return@put call.respond(
                                HttpStatusCode.BadRequest,
                                ApiResponse<String>(success = false, message = "Не найден параметр calendarId")
                            )

                            val request = call.receive<CreateEventRequest>()

                            val event = CalendarEvent(
                                uid = uid,
                                summary = request.summary,
                                description = request.description,
                                startDateTime = LocalDateTime.parse(request.startDateTime),
                                endDateTime = LocalDateTime.parse(request.endDateTime),
                                location = request.location
                            )

                            val dav = Dav(username, password, calendarId)
                            val eventManager = dav.getEventManager()

                            eventManager.updateEvent(event).fold(
                                onSuccess = { updatedEvent ->
                                    val response = EventResponse(
                                        uid = updatedEvent.uid,
                                        summary = updatedEvent.summary,
                                        description = updatedEvent.description,
                                        startDateTime = updatedEvent.startDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                        endDateTime = updatedEvent.endDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                        location = updatedEvent.location
                                    )
                                    call.respond(ApiResponse(success = true, data = response))
                                },
                                onFailure = { error ->
                                    val statusCode = when {
                                        error.message?.contains("авторизации") == true -> HttpStatusCode.Unauthorized
                                        error.message?.contains("запрещен") == true -> HttpStatusCode.Forbidden
                                        error.message?.contains("не найдено") == true -> HttpStatusCode.NotFound
                                        error.message?.contains("Конфликт версий") == true -> HttpStatusCode.PreconditionFailed
                                        else -> HttpStatusCode.BadRequest
                                    }
                                    call.respond(statusCode, ApiResponse<String>(success = false, message = error.message))
                                }
                            )
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ApiResponse<String>(success = false, message = "Ошибка обновления события: ${e.message}")
                            )
                        }
                    }

                    // Удаление события
                    delete("/{uid}") {
                        try {
                            val principal = call.principal<UserIdPrincipal>()!!
                            val username = principal.name
                            val uid = call.parameters["uid"] ?: return@delete call.respond(
                                HttpStatusCode.BadRequest,
                                ApiResponse<String>(success = false, message = "UID события не указан")
                            )

                            val authHeader = call.request.headers["Authorization"]
                            val password = authHeader?.let { header ->
                                val encoded = header.substringAfter("Basic ")
                                val decoded = java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
                                decoded.substringAfter(":")
                            } ?: ""

                            // Получаем календарь ID из заголовка CAL_ID
                            val calendarId = call.parameters["calendarId"] ?: return@delete call.respond(
                                HttpStatusCode.BadRequest,
                                ApiResponse<String>(success = false, message = "Не найден параметр calendarId")
                            )

                            val dav = Dav(username, password, calendarId)
                            val eventManager = dav.getEventManager()

                            eventManager.deleteEvent(uid).fold(
                                onSuccess = {
                                    call.respond(ApiResponse<String>(success = true, message = "Событие успешно удалено"))
                                },
                                onFailure = { error ->
                                    val statusCode = when {
                                        error.message?.contains("авторизации") == true -> HttpStatusCode.Unauthorized
                                        error.message?.contains("запрещен") == true -> HttpStatusCode.Forbidden
                                        error.message?.contains("не найдено") == true -> HttpStatusCode.NotFound
                                        else -> HttpStatusCode.BadRequest
                                    }
                                    call.respond(statusCode, ApiResponse<String>(success = false, message = error.message))
                                }
                            )
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ApiResponse<String>(success = false, message = "Ошибка удаления события: ${e.message}")
                            )
                        }
                    }
                }
            }
        }
    }
}
