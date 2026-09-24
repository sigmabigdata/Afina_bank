#!/usr/bin/env bash
# Проверка состояния базы данных Афина.
set -e
cd "$(dirname "$0")"

CONTAINER="afina-postgres-dev"
DB="afina_db"
USER="app_user"

run() {
    echo ""
    echo "════════════════════════════════════════════════════════════"
    echo "▶ $1"
    echo "════════════════════════════════════════════════════════════"
    docker exec -i "$CONTAINER" psql -U "$USER" -d "$DB" -c "$2"
}

run "Версия и текущий пользователь" \
    "SELECT current_database(), current_user, version();"

run "Размер БД" \
    "SELECT pg_size_pretty(pg_database_size('$DB')) AS db_size;"

run "Таблицы и размеры" \
    "SELECT schemaname, relname,
            pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
            n_live_tup AS rows
     FROM pg_stat_user_tables
     ORDER BY pg_total_relation_size(relid) DESC;"

run "Индексы" \
    "SELECT tablename, indexname FROM pg_indexes
     WHERE schemaname = 'public' ORDER BY tablename, indexname;"

run "Владельцы таблиц" \
    "SELECT tablename, tableowner FROM pg_tables
     WHERE schemaname = 'public' ORDER BY tablename;"

run "Миграции Flyway" \
    "SELECT version, description, success, installed_on
     FROM flyway_schema_history ORDER BY installed_rank;"

run "Данные: users" \
    "SELECT id, email, full_name, role, enabled, last_login_at
     FROM users ORDER BY id;"

run "Данные: documents" \
    "SELECT id, original_name, size, signed, owner_id, uploaded_at
     FROM documents ORDER BY id;"

run "Целостность (FK)" \
    "SELECT
        (SELECT COUNT(*) FROM users) AS users,
        (SELECT COUNT(*) FROM documents) AS documents,
        (SELECT COUNT(*) FROM documents d
         WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = d.owner_id)) AS orphan_docs;"

run "Активные соединения" \
    "SELECT state, count(*) FROM pg_stat_activity
     WHERE datname = '$DB' GROUP BY state;"

run "Расширения" \
    "SELECT extname, extversion FROM pg_extension ORDER BY extname;"

run "Топ-5 запросов по времени" \
    "SELECT LEFT(query, 60) AS query, calls,
            ROUND(total_exec_time::numeric, 2) AS total_ms
     FROM pg_stat_statements
     ORDER BY total_exec_time DESC LIMIT 5;"

echo ""
echo "════════════════════════════════════════════════════════════"
echo "▶ Проверка завершена"
echo "════════════════════════════════════════════════════════════"
