# Jarvis Desktop Launcher

Система запуска Jarvis через иконку в меню/доке Linux.

## Созданные файлы

### 1. jarvis-launch.sh
**Главный скрипт запуска**
- Поднимает Docker-стек (все сервисы)
- Открывает отдельное окно с логами
- Запускает desktop-клиент JavaFX

### 2. jarvis-logs.sh
**Просмотр логов**
- `docker compose logs -f --tail=50`
- Цветной вывод
- Легко копировать для отладки

### 3. jarvis-stop.sh
**Остановка всех сервисов**
- `docker compose down`
- Останавливает все контейнеры

### 4. jarvis.desktop
**Desktop Entry для иконки**
- Добавляется в меню приложений
- Можно перетащить в док
- Имеет 3 экшена: Launch, Stop, View Logs

## Установка

```bash
# 1. Права на выполнение (уже выполнено)
chmod +x jarvis-launch.sh jarvis-logs.sh jarvis-stop.sh

# 2. Создать папку для иконки
mkdir -p icons

# 3. Сохранить иконку
# Иконка сгенерирована AI и находится в artifacts
# Скопируй её в: icons/jarvis-icon.png

# 4. Установить .desktop файл (уже выполнено)
cp jarvis.desktop ~/.local/share/applications/
update-desktop-database ~/.local/share/applications
```

## Использование

### Вариант 1: Через меню
1. Открой меню приложений
2. Найди "Jarvis AI"
3. Кликни - запустится весь стек

### Вариант 2: Через док
1. Найди "Jarvis AI" в меню
2. Перетащи иконку в док/панель
3. Теперь можно запускать одним кликом

### Вариант 3: Правый клик
Правый клик на иконке → выбери действие:
- **Jarvis AI** - запустить
- **Stop Jarvis** - остановить
- **View Logs** - только логи

## Как это работает

### При клике на иконку:

```
1. jarvis-launch.sh
   ├─> docker compose up -d --build
   ├─> Открывает gnome-terminal с jarvis-logs.sh
   └─> java -jar desktop-client-javafx.jar

2. Окно логов (отдельный терминал)
   └─> docker compose logs -f --tail=50
       - Все сервисы в реальном времени
       - Можно скопировать для отладки

3. Desktop-клиент
   └─> Главное окно Jarvis GUI
```

## Отладка

### Если что-то не запустилось:

1. **Открой окно с логами** (оно автоматически открывается)
2. **Найди ошибку** в логах
3. **Скопируй** нужные строки
4. **Скинь** в чат/issue для фикса

### Логи вручную:
```bash
cd /home/kwaqa/IdeaProjects/Jarvis2.0
./jarvis-logs.sh
```

### Проверка сервисов:
```bash
docker compose ps
```

### Остановка вручную:
```bash
./jarvis-stop.sh
# или
docker compose down
```

## Запущенные сервисы

После запуска иконки будут работать:

| Сервис | Port | Описание |
|--------|------|----------|
| api-gateway | 8080 | API Gateway |
| voice-gateway | 8090 | Голосовой ввод/вывод |
| llm-service | 8091 | LLM интеграция |
| planner-service | 8092 | Планировщик (NEW!) |
| life-tracker | 8088 | Трекинг времени |
| analytics-service | 8087 | Аналитика |
| user-profile | 8089 | Профиль пользователя |
| postgres | 5432 | База данных |
| llm-server | 5000 | h2oGPT Python |
| desktop-client | - | JavaFX GUI |

## Автозапуск (опционально)

Добавить в автозапуск при входе в систему:

```bash
# GNOME
gnome-session-properties
# Добавить: /home/kwaqa/IdeaProjects/Jarvis2.0/jar vis-launch.sh

# KDE
~/.config/autostart/jarvis.desktop
```

## Иконка

Сгенерированная иконка Jarvis:
- Размер: 512x512
- Стиль: Arc reactor (Iron Man inspired)
- Цвета: Синий/голубой неон
- Формат: PNG

Сохрани в: `icons/jarvis-icon.png`

## Быстрые команды

```bash
# Запуск
./jarvis-launch.sh

# Остановка
./jarvis-stop.sh

# Логи
./jarvis-logs.sh

# Пересборка Desktop
mvn clean package -pl apps/desktop-client-javafx -am

# Полный рестарт
./jarvis-stop.sh && ./jarvis-launch.sh
```

## Troubleshooting

**Иконка не появилась в меню:**
```bash
update-desktop-database ~/.local/share/applications
# Перезайди в систему
```

**Desktop-клиент не запускается:**
```bash
# Собери проект
mvn clean package -DskipTests
```

**Docker не поднимается:**
```bash
# Проверь Docker
docker ps
systemctl status docker
```

**Окно логов не открывается:**
```bash
# Установи gnome-terminal
sudo apt install gnome-terminal
```

## Готово! 🎉

Теперь у тебя:
- ✅ Иконка Jarvis в меню
- ✅ Запуск одним кликом
- ✅ Автоматическое окно логов
- ✅ Desktop GUI
- ✅ Быстрая остановка

**Кликай на иконку и работай с Jarvis!**
