#!/usr/bin/env bash
# Восстановление БД Афина (prod).
set -e
cd "$(dirname "$0")"

if [ -z "$1" ] || [ ! -f "$1" ]; then
    echo "Использование: $0 backups/afina_YYYYMMDD_HHMMSS.sql.gz"
    echo ""
    echo "Доступные бэкапы:"
    ls -la backups/ 2>/dev/null || echo "  (нет)"
    exit 1
fi

echo "⚠️  Восстановление из $1. Все данные будут заменены."
read -p "Введи yes для подтверждения: " ans
[ "$ans" = "yes" ] || { echo "Отменено."; exit 1; }

echo "▶ Останавливаю app"
docker compose -f docker-compose-prod.yml --env-file .env.prod stop app

echo "▶ Восстанавливаю БД"
gunzip -c "$1" | docker exec -i afina-postgres psql \
    -U afina_migrator -d afina_db -q --single-transaction

echo "▶ Восстанавливаю владельцев"
docker exec -i afina-postgres psql -U postgres -d afina_db -q \
    < db/fix-ownership.sql 2>/dev/null || true

echo "▶ Запускаю app"
docker compose -f docker-compose-prod.yml --env-file .env.prod start app

echo "✅ Готово"
