package com.nano.Service

import at.bitfire.dav4jvm.BasicDigestAuthHandler
import at.bitfire.dav4jvm.DavCalendar
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.property.CalendarData
import io.ktor.http.Url
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory.getLogger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.log

enum class DAVHOST(val url: String) {
    BAIKAL("https://baikal.illegalfiles.icu/dav.php"),
}

data class CalendarEvent(
    val uid: String = UUID.randomUUID().toString(),
    val summary: String,
    val description: String? = null,
    val startDateTime: LocalDateTime,
    val endDateTime: LocalDateTime,
    val location: String? = null
)

class Dav(
    val username: String,
    val password: String,
    val calendarName: String,
    server: DAVHOST = DAVHOST.BAIKAL,
) {
    val baseUrl = Url(server.url)

    val authHandler = BasicDigestAuthHandler(
        domain = null, // Убираем ограничение по домену
        username = username,
        password = password,
    )

    val client = OkHttpClient.Builder()
        .followRedirects(false)
        .authenticator(authHandler)
        .addNetworkInterceptor(authHandler)
        .build()

    val calDav = DavCalendar(
        client,
        location = baseUrl.toString().toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment("calendars")
            ?.build() ?: throw IllegalArgumentException("Invalid URL: $baseUrl"),
    )

    /**
     * Получает менеджер для работы с событиями календаря
     */
    fun getEventManager(): CalendarEventManager {
        return CalendarEventManager(this)
    }
}

/**
 * Менеджер для работы с событиями календаря.
 * Реализует основные CRUD операции с использованием библиотеки dav4jvm
 */
class CalendarEventManager(private val dav: Dav) {

    private val logger = getLogger("Dav")

    private fun createICalendarEvent(event: CalendarEvent): String {
        val dtFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        val now = LocalDateTime.now().format(dtFormatter)

        return """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//WebDAV Service//CalDAV Client//EN
BEGIN:VEVENT
UID:${event.uid}
DTSTAMP:${now}Z
DTSTART:${event.startDateTime.format(dtFormatter)}Z
DTEND:${event.endDateTime.format(dtFormatter)}Z
SUMMARY:${event.summary}
${if (!event.description.isNullOrEmpty()) "DESCRIPTION:${event.description}" else ""}
${if (!event.location.isNullOrEmpty()) "LOCATION:${event.location}" else ""}
END:VEVENT
END:VCALENDAR""".trimIndent()
    }

    /**
     * Добавляет новое событие в календарь
     */
    fun addEvent(event: CalendarEvent): Result<CalendarEvent> {
        return try {
            val iCalData = createICalendarEvent(event)
            val eventUrl = dav.calDav.location.newBuilder()
                .addPathSegment(dav.username)
                .addPathSegment(dav.calendarName)
                .addPathSegment("${event.uid}.ics")
                .build()

            val davResource = DavResource(dav.client, eventUrl)
            val requestBody = iCalData.toRequestBody("text/calendar; charset=utf-8".toMediaType())

            var responseCode: Int? = null
            var responseMessage: String? = null

            davResource.put(requestBody) { response ->
                responseCode = response.code
                responseMessage = response.message
            }

            when {
                responseCode == null -> Result.failure(Exception("Не удалось получить ответ от сервера"))
                responseCode in 200..299 -> Result.success(event)
                responseCode == 401 -> Result.failure(Exception("Ошибка авторизации: проверьте логин и пароль"))
                responseCode == 403 -> Result.failure(Exception("Доступ запрещен: недостаточно прав для записи в календарь '${dav.calendarName}'"))
                responseCode == 404 -> Result.failure(Exception("Календарь '${dav.calendarName}' не найден"))
                responseCode == 409 -> Result.failure(Exception("Конфликт: событие с UID '${event.uid}' уже существует"))
                responseCode in 500..599 -> Result.failure(Exception("Ошибка сервера ($responseCode): $responseMessage"))
                else -> Result.failure(Exception("HTTP $responseCode: $responseMessage"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Ошибка при добавлении события: ${e.message}", e))
        }
    }

    /**
     * Получает событие по UID
     */
    fun getEvent(uid: String): Result<CalendarEvent?> {
        return try {
            val eventUrl = dav.calDav.location.newBuilder()
                .addPathSegment(dav.username)
                .addPathSegment(dav.calendarName)
                .addPathSegment("$uid.ics")
                .build()

            val davResource = DavResource(dav.client, eventUrl)
            var eventData: String? = null
            var responseCode: Int? = null
            var responseMessage: String? = null

            davResource.get("text/calendar", null) { response ->
                responseCode = response.code
                responseMessage = response.message
                if (response.isSuccessful) {
                    eventData = response.body?.string()
                }
            }

            when {
                responseCode == null -> Result.failure(Exception("Не удалось получить ответ от сервера"))
                responseCode == 200 -> {
                    val event = eventData?.let { parseICalendarEvent(it, uid) }
                    Result.success(event)
                }
                responseCode == 401 -> Result.failure(Exception("Ошибка авторизации: проверьте логин и пароль"))
                responseCode == 403 -> Result.failure(Exception("Доступ запрещен: недостаточно прав для чтения календаря '${dav.calendarName}'"))
                responseCode == 404 -> Result.failure(Exception("Событие с UID '$uid' не найдено в календаре '${dav.calendarName}'"))
                responseCode in 500..599 -> Result.failure(Exception("Ошибка сервера ($responseCode): $responseMessage"))
                else -> Result.failure(Exception("HTTP $responseCode: $responseMessage"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Ошибка при получении события: ${e.message}", e))
        }
    }

    /**
     * Обновляет существующее событие
     */
    fun updateEvent(event: CalendarEvent): Result<CalendarEvent> {
        return try {
            val iCalData = createICalendarEvent(event)
            val eventUrl = dav.calDav.location.newBuilder()
                .addPathSegment(dav.username)
                .addPathSegment(dav.calendarName)
                .addPathSegment("${event.uid}.ics")
                .build()

            val davResource = DavResource(dav.client, eventUrl)
            val requestBody = iCalData.toRequestBody("text/calendar; charset=utf-8".toMediaType())

            var responseCode: Int? = null
            var responseMessage: String? = null

            davResource.put(requestBody) { response ->
                responseCode = response.code
                responseMessage = response.message
            }

            when {
                responseCode == null -> Result.failure(Exception("Не удалось получить ответ от сервера"))
                responseCode in 200..299 -> Result.success(event)
                responseCode == 401 -> Result.failure(Exception("Ошибка авторизации: проверьте логин и пароль"))
                responseCode == 403 -> Result.failure(Exception("Доступ запрещен: недостаточно прав для изменения календаря '${dav.calendarName}'"))
                responseCode == 404 -> Result.failure(Exception("Событие с UID '${event.uid}' не найдено в календаре '${dav.calendarName}'"))
                responseCode == 412 -> Result.failure(Exception("Конфликт версий: событие было изменено другим пользователем"))
                responseCode in 500..599 -> Result.failure(Exception("Ошибка сервера ($responseCode): $responseMessage"))
                else -> Result.failure(Exception("HTTP $responseCode: $responseMessage"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Ошибка при обновлении события: ${e.message}", e))
        }
    }

    /**
     * Удаляет событие по UID
     */
    fun deleteEvent(uid: String): Result<Unit> {
        return try {
            val eventUrl = dav.calDav.location.newBuilder()
                .addPathSegment(dav.username)
                .addPathSegment(dav.calendarName)
                .addPathSegment("$uid.ics")
                .build()

            val davResource = DavResource(dav.client, eventUrl)
            var responseCode: Int? = null
            var responseMessage: String? = null

            davResource.delete { response ->
                responseCode = response.code
                responseMessage = response.message
            }

            when {
                responseCode == null -> Result.failure(Exception("Не удалось получить ответ от сервера"))
                responseCode in 200..299 -> Result.success(Unit)
                responseCode == 401 -> Result.failure(Exception("Ошибка авторизации: проверьте логин и пароль"))
                responseCode == 403 -> Result.failure(Exception("Доступ запрещен: недостаточно прав для удаления из календаря '${dav.calendarName}'"))
                responseCode == 404 -> Result.failure(Exception("Событие с UID '$uid' не найдено в календаре '${dav.calendarName}'"))
                responseCode in 500..599 -> Result.failure(Exception("Ошибка сервера ($responseCode): $responseMessage"))
                else -> Result.failure(Exception("HTTP $responseCode: $responseMessage"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Ошибка при удалении события: ${e.message}", e))
        }
    }

    /**
     * Получает список всех событий из календаря
     */
    fun getAllEvents(): Result<List<CalendarEvent>> {
        return try {
            val calendarUrl = dav.calDav.location.newBuilder()
                .addPathSegment(dav.username)
                .addPathSegment(dav.calendarName)
                .build()

            val calendar = DavCalendar(dav.client, calendarUrl)
            val events = mutableListOf<CalendarEvent>()
            var hasError = false
            var errorMessage = ""

            // Простое получение всех ресурсов календаря
            calendar.propfind(1, CalendarData.NAME) { response, relation ->
                try {
                    if (relation == at.bitfire.dav4jvm.Response.HrefRelation.MEMBER) {
                        response[CalendarData::class.java]?.let { calData ->
                            val iCalContent = calData.iCalendar ?: return@propfind
                            val uid = extractUidFromICal(iCalContent)
                            uid?.let {
                                parseICalendarEvent(iCalContent, it)?.let { event ->
                                    events.add(event)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    hasError = true
                    errorMessage = "Ошибка при обработке события: ${e.message}"
                }
            }

            when {
                hasError -> Result.failure(Exception(errorMessage))
                events.isEmpty() -> Result.success(emptyList())
                else -> Result.success(events)
            }
        } catch (e: Exception) {
            when {
                e.message?.contains("401") == true -> Result.failure(Exception("Ошибка авторизации: проверьте логин и пароль"))
                e.message?.contains("403") == true -> Result.failure(Exception("Доступ запрещен: недостаточно прав для чтения календаря '${dav.calendarName}'"))
                e.message?.contains("404") == true -> Result.failure(Exception("Календарь '${dav.calendarName}' не найден"))
                else -> Result.failure(Exception("Ошибка при получении списка событий: ${e.message}", e))
            }
        }
    }

    /**
     * Получает события за определенный период
     */
    fun getEventsByDateRange(startDate: LocalDateTime, endDate: LocalDateTime): List<CalendarEvent> {
        return getAllEvents().getOrDefault(emptyList()).filter { event ->
            event.startDateTime.isAfter(startDate.minusDays(1)) &&
            event.endDateTime.isBefore(endDate.plusDays(1))
        }
    }

    private fun parseICalendarEvent(iCalData: String, uid: String): CalendarEvent? {
        return try {
            val lines = iCalData.lines()
            var summary = ""
            var description: String? = null
            var location: String? = null
            var startDateTime: LocalDateTime? = null
            var endDateTime: LocalDateTime? = null

            val dtFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            val dtFormatterLocal = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

            for (line in lines) {
                when {
                    line.startsWith("SUMMARY:") -> summary = line.substringAfter("SUMMARY:")
                    line.startsWith("DESCRIPTION:") -> description = line.substringAfter("DESCRIPTION:")
                    line.startsWith("LOCATION:") -> location = line.substringAfter("LOCATION:")
                    line.startsWith("DTSTART:") -> {
                        val dtStart = line.substringAfter("DTSTART:")
                        startDateTime = try {
                            if (dtStart.endsWith("Z")) {
                                LocalDateTime.parse(dtStart, dtFormatter)
                            } else {
                                LocalDateTime.parse(dtStart, dtFormatterLocal)
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                    line.startsWith("DTEND:") -> {
                        val dtEnd = line.substringAfter("DTEND:")
                        endDateTime = try {
                            if (dtEnd.endsWith("Z")) {
                                LocalDateTime.parse(dtEnd, dtFormatter)
                            } else {
                                LocalDateTime.parse(dtEnd, dtFormatterLocal)
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }

            if (startDateTime != null && endDateTime != null) {
                CalendarEvent(
                    uid = uid,
                    summary = summary,
                    description = description,
                    startDateTime = startDateTime,
                    endDateTime = endDateTime,
                    location = location
                )
            } else null
        } catch (_: Exception) {
            logger.info("Ошибка при парсинге события")
            null
        }
    }

    private fun extractUidFromICal(iCalData: String): String? {
        return iCalData.lines().find { it.startsWith("UID:") }?.substringAfter("UID:")
    }
}