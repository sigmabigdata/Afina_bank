#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "❌ Файл .env не найден!"
  exit 1
fi

# Экспортируем все переменные из .env — так кириллица читается как UTF-8
set -a
# shellcheck disable=SC1091
source .env
set +a

echo "▶ ADMIN_EMAIL=${ADMIN_EMAIL}"
echo "▶ ADMIN_FULLNAME=${ADMIN_FULLNAME}"
echo "▶ SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}"
echo "▶ Запуск..."

exec mvn spring-boot:run
