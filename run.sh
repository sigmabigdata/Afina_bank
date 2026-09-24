#!/usr/bin/env bash
# Запуск приложения с выводом логов в терминал (через tee) + сохранение в файл.
# Терминал должен оставаться открытым. Для остановки — Ctrl+C.

set -e
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "❌ Файл .env не найден!"
  exit 1
fi

mkdir -p logs

set -a
# shellcheck disable=SC1091
source .env
set +a

echo "▶ Профиль:          ${SPRING_PROFILES_ACTIVE}"
echo "▶ Base URL:         ${APP_BASE_URL}"
echo "▶ Хранилище:        ${APP_STORAGE_PATH}"
echo "▶ Файл админов:     ${ADMINS_FILE}"
echo "▶ Режим почты:      ${MAIL_MODE}"
echo "▶ Лог:              $(pwd)/logs/app.log (дублируется сюда)"
echo "▶ Запуск..."

# Прибиваем всё, что висит на 8080
lsof -ti :8080 2>/dev/null | xargs kill -9 2>/dev/null || true

# caffeinate -i не даёт macOS усыплять процесс
if command -v caffeinate >/dev/null 2>&1; then
    WRAP="caffeinate -i"
    echo "▶ caffeinate:        включён"
else
    WRAP=""
    echo "▶ caffeinate:        не найден, работаем без него"
fi

echo "▶ Ctrl+C — остановить"
echo "────────────────────────────────────────────────────────────"

# tee — вывод и в терминал, и в файл (с буферизацией строк)
exec $WRAP mvn spring-boot:run 2>&1 | tee logs/app.log
