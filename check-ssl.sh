#!/usr/bin/env bash
# Проверка срока SSL-сертификата Афина.
# Возвращает:
#   0 — всё ок
#   1 — сертификат истекает менее чем за WARN_DAYS дней
#   2 — сертификат уже истёк / ошибка

set -e
cd "$(dirname "$0")"

WARN_DAYS=14
DOMAIN=$(grep -E '^APP_DOMAIN=' .env.prod 2>/dev/null | cut -d= -f2)
TLS_MODE=$(grep -E '^TLS_MODE=' .env.prod 2>/dev/null | cut -d= -f2 || echo letsencrypt)

if [ -z "$DOMAIN" ]; then
    echo "✗ APP_DOMAIN не задан в .env.prod"
    exit 2
fi

# --- Получить срок действия ---
if [ "${TLS_MODE:-letsencrypt}" = "custom" ]; then
    CERT_FILE="caddy-certs/fullchain.pem"
    if [ ! -f "$CERT_FILE" ]; then
        echo "✗ TLS_MODE=custom, но $CERT_FILE отсутствует"
        exit 2
    fi
    END_DATE=$(openssl x509 -in "$CERT_FILE" -noout -enddate | cut -d= -f2)
else
    # Let's Encrypt: сертификат в volume caddy_data
    CERT_FILE=$(docker compose -f docker-compose-prod.yml --env-file .env.prod \
        exec -T caddy sh -c \
        "find /data/caddy/certificates -name '${DOMAIN}.crt' 2>/dev/null | head -1" 2>/dev/null || echo "")

    if [ -z "$CERT_FILE" ]; then
        echo "⚠ сертификат для $DOMAIN не найден в caddy_data (ещё не выпущен?)"
        exit 0
    fi

    END_DATE=$(docker compose -f docker-compose-prod.yml --env-file .env.prod \
        exec -T caddy sh -c "openssl x509 -in $CERT_FILE -noout -enddate" 2>/dev/null \
        | cut -d= -f2 || echo "")
fi

if [ -z "$END_DATE" ]; then
    echo "✗ не удалось прочитать дату истечения"
    exit 2
fi

# --- Посчитать дни ---
END_EPOCH=$(date -d "$END_DATE" +%s 2>/dev/null || date -jf "%b %d %T %Y %Z" "$END_DATE" +%s)
NOW_EPOCH=$(date +%s)
DAYS_LEFT=$(( (END_EPOCH - NOW_EPOCH) / 86400 ))

echo "Domain:    $DOMAIN"
echo "TLS mode:  ${TLS_MODE:-letsencrypt}"
echo "Expires:   $END_DATE"
echo "Days left: $DAYS_LEFT"

if [ "$DAYS_LEFT" -lt 0 ]; then
    echo "✗ СЕРТИФИКАТ ИСТЁК"
    exit 2
elif [ "$DAYS_LEFT" -lt "$WARN_DAYS" ]; then
    echo "⚠ до истечения менее $WARN_DAYS дней"
    exit 1
else
    echo "✓ ок"
    exit 0
fi
