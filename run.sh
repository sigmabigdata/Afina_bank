#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "❌ Файл .env не найден!"
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

echo "▶ Профиль:          ${SPRING_PROFILES_ACTIVE}"
echo "▶ Base URL:         ${APP_BASE_URL}"
echo "▶ Хранилище:        ${APP_STORAGE_PATH}"
echo "▶ Файл админов:     ${ADMINS_FILE}"
echo "▶ Режим почты:      ${MAIL_MODE}"
echo "▶ Запуск..."
exec mvn spring-boot:run
