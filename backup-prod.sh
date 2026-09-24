#!/usr/bin/env bash
# Бэкап БД Афина (prod).
set -e
cd "$(dirname "$0")"

mkdir -p backups
STAMP=$(date +%Y%m%d_%H%M%S)
FILE="backups/afina_${STAMP}.sql.gz"

docker exec afina-postgres pg_dump \
    -U afina_migrator -d afina_db \
    --clean --if-exists \
    --no-owner --no-privileges \
    | gzip > "$FILE"

echo "[$(date)] Бэкап: $FILE ($(du -h "$FILE" | cut -f1))"

# Удаляем старше 30 дней
find backups -name "afina_*.sql.gz" -mtime +30 -delete 2>/dev/null || true
