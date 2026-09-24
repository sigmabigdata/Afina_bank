#!/usr/bin/env bash
# ============================================================
# Афина · Первичная настройка БД (DBA-операция)
# ============================================================
# Запускается ОДИН РАЗ после первого развёртывания PostgreSQL.
# Под пользователем-суперюзером (app_user).
#
# Что делает:
#   1. Проверяет, что БД и роли созданы
#   2. Устанавливает расширение pgaudit
#   3. Настраивает уровень аудита для ролей приложения
#
# Идемпотентно: можно запускать повторно без вреда.
# ============================================================

set -e
cd "$(dirname "$0")"

CONTAINER="afina-postgres-dev"
DB="afina_db"

echo "════════════════════════════════════════════════════════════"
echo "▶ Настройка БД Афина"
echo "════════════════════════════════════════════════════════════"

echo ""
echo "▶ 1/4 — Проверяю контейнер"
if ! docker ps --format '{{.Names}}' | grep -q "$CONTAINER"; then
  echo "❌ Контейнер $CONTAINER не запущен. Запусти: docker compose -f docker-compose-dev.yml up -d"
  exit 1
fi

echo ""
echo "▶ 2/4 — Проверяю, что роли созданы"
ROLES=$(docker exec "$CONTAINER" psql -U app_user -d "$DB" -t -c "
SELECT count(*) FROM pg_roles
WHERE rolname IN ('afina_migrator', 'afina_app', 'afina_auditor');
" | tr -d ' ')

if [ "$ROLES" != "3" ]; then
  echo "❌ Не все роли созданы (найдено $ROLES из 3)."
  echo "   Проверь db/init/02_roles.sql — он выполняется при первом старте контейнера."
  exit 1
fi
echo "   ✅ Все 3 роли на месте"

echo ""
echo "▶ 3/4 — Устанавливаю pgaudit"
docker exec "$CONTAINER" psql -U app_user -d "$DB" -c "CREATE EXTENSION IF NOT EXISTS pgaudit;" > /dev/null
echo "   ✅ Расширение установлено"

echo ""
echo "▶ 4/4 — Настраиваю аудит для ролей"
docker exec -i "$CONTAINER" psql -U app_user -d "$DB" -q < db/setup/pgaudit-setup.sql

echo ""
echo "▶ Финальная проверка:"
docker exec "$CONTAINER" psql -U app_user -d "$DB" -c "
SELECT rolname, rolconfig
FROM pg_roles
WHERE rolname IN ('afina_app', 'afina_migrator', 'afina_auditor')
ORDER BY rolname;
"

echo ""
echo "✅ Настройка БД завершена."
echo "▶ Теперь можно запускать приложение: ./run.sh"
