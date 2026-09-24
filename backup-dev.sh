#!/usr/bin/env bash
# Бэкап БД Афина.
# Использует --clean --if-exists: в дампе будут команды DROP TABLE,
# поэтому восстановление работает и на непустой базе.
set -e
cd "$(dirname "$0")"

CONTAINER="afina-postgres-dev"
DB="afina_db"

mkdir -p backups
STAMP=$(date +%Y%m%d_%H%M%S)
FILE="backups/afina_${STAMP}.sql.gz"

docker exec "$CONTAINER" pg_dump \
    -U app_user -d "$DB" \
    --clean --if-exists \
    --no-owner --no-privileges \
    | gzip > "$FILE"

echo "▶ Бэкап сохранён: $FILE ($(du -h "$FILE" | cut -f1))"

# Удаляем бэкапы старше 7 дней
find backups -name "afina_*.sql.gz" -mtime +7 -delete 2>/dev/null || true
