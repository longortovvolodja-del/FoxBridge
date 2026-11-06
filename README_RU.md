# FoxBridge 1.0.0

Плагин-мост для Paper 1.18.2 / Java 17. Предоставляет ограниченный API для диагностики сервера.

## Безопасность
- По умолчанию слушает только 127.0.0.1.
- Удалённый bind без TLS запрещён.
- Файловый доступ ограничен каталогами plugins/ и logs/.
- Загрузка JAR идёт только в plugins/FoxBridge/staging/ и не устанавливает файл автоматически.
- Установка JAR требует отдельного подтверждения из консоли.
- Произвольные системные команды и удаление файлов через API не поддерживаются.
- В security.yml хранится только SHA-256 хеш ключа.

## Сборка
GitHub Actions собирает FoxBridge-1.0.0.jar на Java 17. Результат доступен во вкладке Actions → выбранный запуск → Artifacts.
Локально: mvn -B clean package

## Установка
1. Скачайте FoxBridge-1.0.0.jar из артефактов успешной сборки.
2. Загрузите JAR в plugins/ через SFTP.
3. Полностью перезапустите Paper.
4. Сохраните ключ, который плагин один раз выведет в консоль.
5. Для отзыва ключа выполните foxbridge resetkey в консоли.

## Команды
- /foxbridge status
- /foxbridge reload
- foxbridge resetkey — только консоль.
- foxbridge approve <имя.jar> — только консоль; переносит файл из staging в plugins/ и сохраняет резервную копию.

## Удалённый доступ
Для внешнего подключения выделите порт у хостинга, настройте PKCS12 keystore, включите tls.enabled, задайте собственный tls.password и bind_address 0.0.0.0. Сертификат должен быть доверенным клиенту. Не отключайте проверку TLS. Успешный bind не гарантирует, что порт открыт хостингом.

## API
Все запросы требуют Authorization: Bearer <key>.
- GET /v1/status
- GET /v1/plugins
- GET /v1/files?path=plugins или path=logs
- GET /v1/read?path=plugins/FoxAssistant/config.yml
- GET /v1/logs/latest
- POST /v1/stage с X-Filename: example.jar и бинарным телом запроса (до 64 MiB).

FoxBridge — только серверная часть. Для появления отдельных инструментов ChatGPT следующим этапом потребуется отдельный MCP-сервис/коннектор.
