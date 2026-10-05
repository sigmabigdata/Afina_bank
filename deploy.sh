#!/usr/bin/env bash
# ============================================================
# Афина · Полное развёртывание на чистом VPS
# ============================================================
# Требования:
#   - Ubuntu 22.04/24.04 LTS (x86_64)
#   - root или sudo
#   - Файлы в корне проекта: .env.prod, admins.env, certs/, cryptopro-dist/
#
# Запуск: sudo ./deploy.sh
# ============================================================

set -e
cd "$(dirname "$0")"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; NC='\033[0m'
log()  { echo -e "${BLUE}▶ $*${NC}"; }
ok()   { echo -e "${GREEN}✅ $*${NC}"; }
warn() { echo -e "${YELLOW}⚠️  $*${NC}"; }
err()  { echo -e "${RED}❌ $*${NC}"; exit 1; }

# ----- 0. Проверки -----
log "0/8 — Проверка окружения"
[ "$EUID" -eq 0 ] || err "Запусти через sudo"
[ -f .env.prod ] || err ".env.prod не найден (скопируй из .env.prod.example)"
[ -f admins.env ] || err "admins.env не найден"
[ -f certs/guc_root.cer ] || err "certs/guc_root.cer не найден — положи в certs/"
[ -f certs/kontur-q-2026.crt ] || err "certs/kontur-q-2026.crt не найден"
[ -f cryptopro-dist/linux-amd64_deb.tgz ] || err "cryptopro-dist/linux-amd64_deb.tgz не найден"
[ "$(uname -m)" = "x86_64" ] || err "Требуется x86_64 (CryptoPro не соберётся на ARM)"
ok "Окружение готово"

# ----- TLS: letsencrypt или custom -----
set -a
# shellcheck disable=SC1091
source .env.prod
set +a

if [ "${TLS_MODE:-letsencrypt}" = "custom" ]; then
    [ -f caddy-certs/fullchain.pem ] || err "TLS_MODE=custom, но caddy-certs/fullchain.pem не найден"
    [ -f caddy-certs/privkey.pem ]   || err "TLS_MODE=custom, но caddy-certs/privkey.pem не найден"
    chmod 644 caddy-certs/fullchain.pem
    chmod 600 caddy-certs/privkey.pem
    export TLS_DIRECTIVE="tls /certs/fullchain.pem /certs/privkey.pem"
    ok "TLS: свой сертификат (custom)"
else
    export TLS_DIRECTIVE=""
    ok "TLS: Let's Encrypt (letsencrypt)"
fi

# ----- 1. Обновление системы -----
log "1/8 — apt update && upgrade"
export DEBIAN_FRONTEND=noninteractive
apt-get update -o Acquire::Retries=5
apt-get upgrade -y
ok "Система обновлена"

# ----- 2. Docker -----
log "2/8 — Docker"
if ! command -v docker &>/dev/null; then
    curl -fsSL https://get.docker.com | sh
    systemctl enable --now docker
    ok "Docker установлен"
else
    ok "Docker уже есть"
fi
docker compose version >/dev/null 2>&1 || err "docker compose v2 не установлен"
docker --version
docker compose version

# ----- 3. Утилиты -----
log "3/8 — Утилиты"
apt-get install -y --no-install-recommends \
    ca-certificates curl gnupg lsb-release iptables-persistent fail2ban
ok "Утилиты установлены"

# ----- 4. Firewall -----
log "4/8 — Firewall (iptables-persistent)"
iptables -I INPUT -p tcp --dport 22  -j ACCEPT 2>/dev/null || true
iptables -I INPUT -p tcp --dport 80  -j ACCEPT 2>/dev/null || true
iptables -I INPUT -p tcp --dport 443 -j ACCEPT 2>/dev/null || true
iptables -I INPUT -s 172.18.0.0/16   -j ACCEPT 2>/dev/null || true
netfilter-persistent save 2>/dev/null || iptables-save > /etc/iptables/rules.v4
ok "Firewall настроен"

# ----- 5. Параметры ядра -----
log "5/8 — Параметры ядра"
if ! grep -q '^vm.overcommit_memory' /etc/sysctl.conf; then
    cat >> /etc/sysctl.conf <<'SYSCTL'
vm.overcommit_memory = 1
vm.swappiness = 1
SYSCTL
    sysctl -p
fi
ok "Ядро настроено"

# ----- 6. Каталоги -----
log "6/8 — Каталоги"
mkdir -p storage/documents logs backups
# владелец afina (uid=999) внутри контейнера
chown -R 999:999 storage logs 2>/dev/null || chown -R 1000:1000 storage logs 2>/dev/null || true
chmod -R 775 storage logs
ok "Каталоги: storage/documents, logs, backups"

# ----- 7. Сборка и запуск -----
log "7/8 — Сборка образа (5–15 мин)"
docker compose -f docker-compose-prod.yml --env-file .env.prod build --no-cache app

log "Запуск стека"
docker compose -f docker-compose-prod.yml --env-file .env.prod up -d

log "Ожидание готовности приложения (90 сек)..."
sleep 90
docker compose -f docker-compose-prod.yml --env-file .env.prod ps

# ----- 8. Healthcheck -----
log "8/8 — Healthcheck"
HEALTH=$(curl -s http://localhost:8080/actuator/health 2>/dev/null || echo "DOWN")
echo "Health: $HEALTH"
if echo "$HEALTH" | grep -q '"status":"UP"'; then
    ok "Приложение работает"
else
    warn "Healthcheck не отвечает. Логи:"
    docker compose -f docker-compose-prod.yml --env-file .env.prod logs app --tail=40
fi

# ----- Cron для бэкапов -----
CRON=/etc/cron.d/afina-backup
cat > "$CRON" <<CRONEOF
# Афина: ежедневный бэкап БД в 3:00
0 3 * * * root cd $(pwd) && ./backup-prod.sh >> logs/backup.log 2>&1
CRONEOF
chmod 644 "$CRON"
ok "Cron бэкапа: $CRON (ежедневно в 3:00)"

echo ""
echo -e "${GREEN}════════════════════════════════════════════════${NC}"
echo -e "${GREEN}✅ Развёртывание завершено${NC}"
echo -e "${GREEN}════════════════════════════════════════════════${NC}"
echo ""
echo "Адрес:      $(grep APP_BASE_URL .env.prod | cut -d= -f2)"
echo "Логи app:   docker compose -f docker-compose-prod.yml --env-file .env.prod logs -f app"
echo "Логи caddy: docker compose -f docker-compose-prod.yml --env-file .env.prod logs -f caddy"
echo "Бэкап:      ./backup-prod.sh"
echo "Восстановить: ./restore-prod.sh backups/afina_*.sql.gz"
echo ""
echo "Не забудь: DNS домена должен указывать на этот IP,"
echo "иначе Caddy не получит HTTPS-сертификат."
echo ""
