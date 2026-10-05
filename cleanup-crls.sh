#!/usr/bin/env bash
# Чистка старых CRL из ./crls/.
# Удаляет только автоматически скачанные файлы (auto-*.crl) старше N дней.
# Ручные CRL (kontur-q-2025.crl и т.п.) НЕ трогает.
set -e
cd "$(dirname "$0")"

CRL_DIR="./crls"
MAX_AGE_DAYS="${CRL_MAX_AGE_DAYS:-90}"
LOG_PREFIX="[$(date '+%Y-%m-%d %H:%M:%S')]"

if [ ! -d "$CRL_DIR" ]; then
    echo "$LOG_PREFIX ✗ Директория $CRL_DIR не найдена"
    exit 0
fi

before=$(find "$CRL_DIR" -maxdepth 1 -name 'auto-*.crl' -type f | wc -l)

# Удаляем auto-*.crl старше MAX_AGE_DAYS
deleted=$(find "$CRL_DIR" -maxdepth 1 -name 'auto-*.crl' -type f \
    -mtime +"$MAX_AGE_DAYS" -print -delete | wc -l)

after=$(find "$CRL_DIR" -maxdepth 1 -name 'auto-*.crl' -type f | wc -l)

if [ "$deleted" -gt 0 ]; then
    echo "$LOG_PREFIX ✓ Удалено $deleted auto-*.crl старше $MAX_AGE_DAYS дней (было $before, осталось $after)"
else
    echo "$LOG_PREFIX • Нечего удалять (auto-*.crl: $after, лимит $MAX_AGE_DAYS дней)"
fi
