#!/usr/bin/env bash
# Восстановление БД Афина из бэкапа.
set -e
cd "$(dirname "$0")"

CONTAINER="afina-postgres-dev"
DB="afina_db"

if [ -z "$1" ]; then
  echo "Использование: $0 backups/afina_YYYYMMDD_HHMMSS.sql.gz"
  echo ""
  echo "Доступные бэкапы:"
  ls -la backups/ 2>/dev/null || echo "  (бэкапов нет)"
  exit 1
fi

FILE="$1"
if [ ! -f "$FILE" ]; then
  echo "❌ Файл не найден: $FILE"
  exit 1
fi

SIZE=$(du -h "$FILE" | cut -f1)
echo "▶ Восстанавливаю из: $FILE ($SIZE)"
echo "⚠️  Все данные в $DB будут заменены."
read -p "Продолжить? (yes/no): " ans
if [ "$ans" != "yes" ]; then
  echo "Отменено."
  exit 1
fi

echo ""
echo "▶ 1/4 — Останавливаю приложение"
lsof -ti :8080 2>/dev/null | xargs kill -9 2>/dev/null || true
pkill -f "UkepSignApplication" 2>/dev/null || true
sleep 1

echo "▶ 2/4 — Проверяю контейнер"
if ! docker ps --format '{{.Names}}' | grep -q "$CONTAINER"; then
  echo "❌ Контейнер $CONTAINER не запущен"
  exit 1
fi

echo "▶ 3/4 — Восстанавливаю данные"
gunzip -c "$FILE" | docker exec -i "$CONTAINER" psql -U app_user -d "$DB" -q --single-transaction

echo "▶ 4/4 — Восстанавливаю владельцев и права"
docker exec -i "$CONTAINER" psql -U app_user -d "$DB" -q --single-transaction < db/fix-ownership.sql

echo ""
echo "▶ Проверка:"
docker exec "$CONTAINER" psql -U app_user -d "$DB" -c "
SELECT
    (SELECT COUNT(*) FROM users)     AS users,
    (SELECT COUNT(*) FROM documents) AS documents,
    (SELECT COUNT(*) FROM flyway_schema_history) AS migrations;
"

echo ""
echo "▶ Владельцы таблиц:"
docker exec "$CONTAINER" psql -U app_user -d "$DB" -c "
SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;
"

echo ""
echo "✅ Восстановление завершено."
echo "▶ Запусти приложение: ./run.sh"
