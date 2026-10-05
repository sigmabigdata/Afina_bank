#!/usr/bin/env bash
# ============================================================
# Афина · Обновление CRL
# ============================================================
# Скачивает все CRL из crl-sources.conf в ./crls/
# Идемпотентно: если файл уже есть и не изменился — не перезаписывает.
# ============================================================

set -e
cd "$(dirname "$0")"

CRL_DIR="./crls"
SRC_FILE="./crl-sources.conf"
LOG_PREFIX="[$(date '+%Y-%m-%d %H:%M:%S')]"

mkdir -p "$CRL_DIR"

if [ ! -f "$SRC_FILE" ]; then
    echo "$LOG_PREFIX ❌ Не найден $SRC_FILE"
    exit 1
fi

ok_count=0
fail_count=0
skip_count=0

while IFS='|' read -r name url; do
    # Пропускаем комментарии и пустые строки
    [[ "$name" =~ ^[[:space:]]*# ]] && continue
    [[ -z "$name" ]] && continue
    name=$(echo "$name" | xargs)
    url=$(echo "$url" | xargs)
    [[ -z "$url" ]] && continue

    target="$CRL_DIR/$name"
    tmp=$(mktemp)

    # Скачиваем во временный файл
    if curl -fsS --max-time 30 -o "$tmp" "$url" 2>/dev/null; then
        size_new=$(wc -c < "$tmp")
        if [ "$size_new" -lt 100 ]; then
            echo "$LOG_PREFIX ⚠  $name: пустой ответ ($size_new байт) — пропуск"
            rm -f "$tmp"
            fail_count=$((fail_count + 1))
            continue
        fi

        # Если файл уже есть и совпадает — пропускаем
        if [ -f "$target" ] && cmp -s "$tmp" "$target"; then
            rm -f "$tmp"
            skip_count=$((skip_count + 1))
            continue
        fi

        # Атомарная замена
        mv "$tmp" "$target"
        chmod 644 "$target"
        echo "$LOG_PREFIX ✓ $name обновлён ($size_new байт)"
        ok_count=$((ok_count + 1))
    else
        rm -f "$tmp"
        echo "$LOG_PREFIX ✗ $name: ошибка загрузки $url"
        fail_count=$((fail_count + 1))
    fi
done < "$SRC_FILE"

echo "$LOG_PREFIX Итог: обновлено=$ok_count, без изменений=$skip_count, ошибок=$fail_count"

# Если были ошибки — exit 1 (cron напишет в лог)
[ "$fail_count" -eq 0 ] || exit 1
