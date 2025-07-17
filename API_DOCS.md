## Calendar API Documentation

### Аутентификация
Все эндпоинты требуют Basic авторизации. Используйте ваши учетные данные от CalDAV сервера.

```bash
# Заголовки для всех запросов
Authorization: Basic base64(username:password)
CAL_ID: your_calendar_id
```

### Обязательные заголовки
- `Authorization`: Basic авторизация с креденшлами CalDAV
- `CAL_ID`: Идентификатор календаря (например: "personal", "work", "family")

### Эндпоинты

#### 1. Получить все события
```bash
GET /calendar/events
Authorization: Basic dXNlcm5hbWU6cGFzc3dvcmQ=
CAL_ID: personal

# Ответ:
{
  "success": true,
  "data": [
    {
      "uid": "12345-67890-abcdef",
      "summary": "Важная встреча",
      "description": "Обсуждение проекта",
      "startDateTime": "2025-07-20T10:00:00",
      "endDateTime": "2025-07-20T11:30:00",
      "location": "Конференц-зал"
    }
  ]
}
```

#### 2. Создать новое событие
```bash
POST /calendar/events
Authorization: Basic dXNlcm5hbWU6cGFzc3dvcmQ=
CAL_ID: personal
Content-Type: application/json

{
  "summary": "Новая встреча",
  "description": "Описание встречи",
  "startDateTime": "2025-07-20T14:00:00",
  "endDateTime": "2025-07-20T15:00:00",
  "location": "Офис"
}

# Ответ (201 Created):
{
  "success": true,
  "data": {
    "uid": "generated-uid-12345",
    "summary": "Новая встреча",
    "description": "Описание встречи",
    "startDateTime": "2025-07-20T14:00:00",
    "endDateTime": "2025-07-20T15:00:00",
    "location": "Офис"
  }
}
```

#### 3. Получить событие по UID
```bash
GET /calendar/events/{uid}
Authorization: Basic dXNlcm5hbWU6cGFzc3dvcmQ=
CAL_ID: personal

# Ответ:
{
  "success": true,
  "data": {
    "uid": "12345-67890-abcdef",
    "summary": "Важная встреча",
    "description": "Обсуждение проекта",
    "startDateTime": "2025-07-20T10:00:00",
    "endDateTime": "2025-07-20T11:30:00",
    "location": "Конференц-зал"
  }
}
```

#### 4. Обновить событие
```bash
PUT /calendar/events/{uid}
Authorization: Basic dXNlcm5hbWU6cGFzc3dvcmQ=
CAL_ID: personal
Content-Type: application/json

{
  "summary": "Обновленная встреча",
  "description": "Новое описание",
  "startDateTime": "2025-07-20T15:00:00",
  "endDateTime": "2025-07-20T16:00:00",
  "location": "Новый офис"
}

# Ответ:
{
  "success": true,
  "data": {
    "uid": "12345-67890-abcdef",
    "summary": "Обновленная встреча",
    "description": "Новое описание",
    "startDateTime": "2025-07-20T15:00:00",
    "endDateTime": "2025-07-20T16:00:00",
    "location": "Новый офис"
  }
}
```

#### 5. Удалить событие
```bash
DELETE /calendar/events/{uid}
Authorization: Basic dXNlcm5hbWU6cGFzc3dvcmQ=
CAL_ID: personal

# Ответ:
{
  "success": true,
  "message": "Событие успешно удалено"
}
```

### Примеры с curl

```bash
# Получить все события из календаря "work"
curl -X GET "http://localhost:8080/calendar/events" \
  -H "Authorization: Basic $(echo -n 'username:password' | base64)" \
  -H "CAL_ID: work"

# Создать событие в календаре "personal"
curl -X POST "http://localhost:8080/calendar/events" \
  -H "Authorization: Basic $(echo -n 'username:password' | base64)" \
  -H "CAL_ID: personal" \
  -H "Content-Type: application/json" \
  -d '{
    "summary": "Тестовая встреча",
    "description": "Описание тестовой встречи",
    "startDateTime": "2025-07-20T10:00:00",
    "endDateTime": "2025-07-20T11:00:00",
    "location": "Онлайн"
  }'

# Обновить событие в календаре "family"
curl -X PUT "http://localhost:8080/calendar/events/your-event-uid" \
  -H "Authorization: Basic $(echo -n 'username:password' | base64)" \
  -H "CAL_ID: family" \
  -H "Content-Type: application/json" \
  -d '{
    "summary": "Семейный обед",
    "startDateTime": "2025-07-20T14:00:00",
    "endDateTime": "2025-07-20T15:00:00"
  }'

# Удалить событие из календаря "personal"
curl -X DELETE "http://localhost:8080/calendar/events/your-event-uid" \
  -H "Authorization: Basic $(echo -n 'username:password' | base64)" \
  -H "CAL_ID: personal"
```

### Ошибки

При отсутствии заголовка `CAL_ID`:
```json
{
  "success": false,
  "message": "Заголовок CAL_ID обязателен"
}
```

### Форматы дат
Все даты должны быть в формате ISO LocalDateTime: `YYYY-MM-DDTHH:mm:ss`
Например: `2025-07-20T14:30:00`

### Поддерживаемые календари
API поддерживает работу с любыми календарями, доступными пользователю в CalDAV:
- `personal` - личный календарь
- `work` - рабочий календарь  
- `family` - семейный календарь
- Любые другие календари, созданные пользователем
