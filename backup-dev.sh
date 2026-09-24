#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

mkdir -p backups
STAMP=$(date +%Y%m%d_%H%M%S)
FILE="backups/afina_${STAMP}.sql.gz"

docker exec afina-postgres-dev pg_dump -U app_user -d afina_db | gzip > "$FILE"

echo "▶ Бэкап сохранён: $FILE ($(du -h "$FILE" | cut -f1))"

# Удаляем бэкапы старше 7 дней
find backups -name "afina_*.sql.gz" -mtime +7 -delete 2>/dev/null || true
