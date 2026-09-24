#!/usr/bin/env bash
# Тест: создать данные → бэкап → удалить → восстановить → проверить.
set -e
cd "$(dirname "$0")"

CONTAINER="afina-postgres-dev"
DB="afina_db"

echo "════════════════════════════════════════════"
echo "▶ Тест бэкапа"
echo "════════════════════════════════════════════"

echo ""
echo "▶ 0/5 — Останавливаю приложение"
lsof -ti :8080 2>/dev/null | xargs kill -9 2>/dev/null || true
pkill -f "UkepSignApplication" 2>/dev/null || true
sleep 1

echo ""
echo "▶ 1/5 — Создаю тестовые данные"
docker exec "$CONTAINER" psql -U app_user -d "$DB" -c "
INSERT INTO users (email, full_name, role, enabled)
VALUES ('backup-test@afina.local', 'Тестовый Пользователь', 'ROLE_USER', true)
ON CONFLICT (email) DO NOTHING;
" > /dev/null
BEFORE=$(docker exec "$CONTAINER" psql -U app_user -d "$DB" -t -c "SELECT COUNT(*) FROM users;" | tr -d ' ')
echo "   Пользователей до бэкапа: $BEFORE"

echo ""
echo "▶ 2/5 — Создаю бэкап"
mkdir -p backups
STAMP=$(date +%Y%m%d_%H%M%S)
FILE="backups/test_${STAMP}.sql.gz"
docker exec "$CONTAINER" pg_dump \
    -U app_user -d "$DB" \
    --clean --if-exists \
    --no-owner --no-privileges \
    | gzip > "$FILE"
echo "   $FILE ($(du -h "$FILE" | cut -f1))"

echo ""
echo "▶ 3/5 — Удаляю тестовые данные"
docker exec "$CONTAINER" psql -U app_user -d "$DB" -c "
DELETE FROM users WHERE email = 'backup-test@afina.local';
" > /dev/null
AFTER=$(docker exec "$CONTAINER" psql -U app_user -d "$DB" -t -c "SELECT COUNT(*) FROM users;" | tr -d ' ')
echo "   Пользователей после удаления: $AFTER"

echo ""
echo "▶ 4/5 — Восстанавливаю из бэкапа"
gunzip -c "$FILE" | docker exec -i "$CONTAINER" psql -U app_user -d "$DB" -q --single-transaction

# ВАЖНО: после restore владельцы таблиц становятся app_user.
# Выравниваем обратно на afina_migrator и раздаём права.
docker exec -i "$CONTAINER" psql -U app_user -d "$DB" -q --single-transaction < db/fix-ownership.sql

echo ""
echo "▶ 5/5 — Проверяю результат"
RESTORED=$(docker exec "$CONTAINER" psql -U app_user -d "$DB" -t -c "SELECT COUNT(*) FROM users;" | tr -d ' ')
TEST_ROW=$(docker exec "$CONTAINER" psql -U app_user -d "$DB" -t -c "
SELECT COUNT(*) FROM users WHERE email = 'backup-test@afina.local';
" | tr -d ' ')
echo "   Пользователей после восстановления: $RESTORED"
echo "   Тестовая запись найдена: $TEST_ROW"

# Проверка владельцев
OWNER=$(docker exec "$CONTAINER" psql -U app_user -d "$DB" -t -c "
SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='flyway_schema_history';
" | tr -d ' ')
echo "   Владелец flyway_schema_history: $OWNER"

# Очистка тестовой записи
docker exec "$CONTAINER" psql -U app_user -d "$DB" -c "
DELETE FROM users WHERE email = 'backup-test@afina.local';
" > /dev/null

if [ "$TEST_ROW" = "1" ] && [ "$OWNER" = "afina_migrator" ]; then
    echo ""
    echo "✅ Тест бэкапа успешен"
    rm -f "$FILE"
else
    echo ""
    echo "❌ Тест провален"
    echo "   Файл бэкапа: $FILE"
    exit 1
fi
