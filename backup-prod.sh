#!/usr/bin/env bash
# Бэкап БД + файлов Афина (prod).
set -e
cd "$(dirname "$0")"

STAMP=$(date +%Y%m%d_%H%M%S)
mkdir -p backups

# --- 1. Дамп БД ---
DB_FILE="backups/afina_${STAMP}.sql.gz"
docker exec afina-postgres pg_dump \
    -U afina_migrator -d afina_db \
    --clean --if-exists \
    --no-owner --no-privileges \
    | gzip > "$DB_FILE"
echo "[$(date)] БД: $DB_FILE ($(du -h "$DB_FILE" | cut -f1))"

# --- 2. Файлы (уже зашифрованы AES-GCM — можно бэкапить как есть) ---
FILES_FILE="backups/storage_${STAMP}.tar.gz"
tar czf "$FILES_FILE" -C . storage/documents/ 2>/dev/null || true
echo "[$(date)] Файлы: $FILES_FILE"

# --- 3. Ротация (старше 30 дней) ---
find backups -name "afina_*.sql.gz"    -mtime +30 -delete 2>/dev/null || true
find backups -name "storage_*.tar.gz" -mtime +30 -delete 2>/dev/null || true
