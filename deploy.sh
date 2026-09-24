#!/usr/bin/env bash
# ============================================================
# Афина · Автоматизированное развёртывание на VPS
# ============================================================
# Требования:
#   - Ubuntu 22.04 / 24.04 LTS (x86_64)
#   - Root или sudo
#   - Файлы .env.prod и admins.env заполнены
#   - cryptopro-dist/linux-amd64_deb.tgz в корне проекта
#
# Запуск: sudo ./deploy.sh
# ============================================================

set -e
cd "$(dirname "$0")"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()    { echo -e "${BLUE}▶ $*${NC}"; }
ok()     { echo -e "${GREEN}✅ $*${NC}"; }
warn()   { echo -e "${YELLOW}⚠️  $*${NC}"; }
err()    { echo -e "${RED}❌ $*${NC}"; exit 1; }

# ============================================================
# 0. Проверки перед запуском
# ============================================================
log "0/8 — Проверка окружения"

if [ "$EUID" -ne 0 ]; then
    err "Скрипт должен запускаться от root или через sudo"
fi

if [ ! -f ".env.prod" ]; then
    err "Файл .env.prod не найден. Скопируй .env.prod.example и заполни."
fi

if [ ! -f "admins.env" ]; then
    err "Файл admins.env не найден. Скопируй admins.env.example и заполни."
fi

if [ ! -f "cryptopro-dist/linux-amd64_deb.tgz" ]; then
    err "Дистрибутив CryptoPro не найден в cryptopro-dist/"
fi

if [ ! -f "kontur-q-2025.crl" ]; then
    warn "CRL-файл kontur-q-2025.crl не найден — проверка подписи не будет работать"
fi

ARCH=$(uname -m)
if [ "$ARCH" != "x86_64" ]; then
    err "Требуется x86_64. Текущая архитектура: $ARCH. CryptoPro не соберётся на ARM."
fi

ok "Окружение готово"

# ============================================================
# 1. Обновление системы
# ============================================================
log "1/8 — Обновление Ubuntu"
export DEBIAN_FRONTEND=noninteractive
apt-get update -o Acquire::Retries=5
apt-get upgrade -y
ok "Система обновлена"

# ============================================================
# 2. Установка Docker
# ============================================================
log "2/8 — Установка Docker"
if ! command -v docker &> /dev/null; then
    curl -fsSL https://get.docker.com | sh
    systemctl enable docker
    systemctl start docker
    ok "Docker установлен"
else
    ok "Docker уже установлен"
fi

if ! docker compose version &> /dev/null; then
    err "Docker Compose v2 не установлен. Установи вручную: apt install docker-compose-plugin"
fi

docker --version
docker compose version

# ============================================================
# 3. Установка утилит
# ============================================================
log "3/8 — Установка утилит"
apt-get install -y --no-install-recommends \
    ca-certificates curl gnupg lsb-release ufw fail2ban

ok "Утилиты установлены"

# ============================================================
# 4. Firewall (UFW)
# ============================================================
log "4/8 — Настройка firewall"
ufw --force enable
ufw allow 22/tcp comment 'SSH'
ufw allow 80/tcp comment 'HTTP (Let'\''s Encrypt)'
ufw allow 443/tcp comment 'HTTPS'
ufw default deny incoming
ufw default allow outgoing
ufw status verbose
ok "Firewall настроен"

# ============================================================
# 5. Параметры ядра для PostgreSQL
# ============================================================
log "5/8 — Параметры ядра для PostgreSQL"
if ! grep -q "vm.overcommit_memory" /etc/sysctl.conf; then
    cat >> /etc/sysctl.conf <<'SYSCTL'
# PostgreSQL
vm.overcommit_memory = 2
vm.swappiness = 1
SYSCTL
    sysctl -p
fi
ok "Параметры ядра применены"

# ============================================================
# 6. Создание структуры каталогов
# ============================================================
log "6/8 — Подготовка каталогов"
mkdir -p storage/documents logs backups
chmod 755 storage logs backups
ok "Каталоги готовы"

# ============================================================
# 7. Сборка и запуск стека
# ============================================================
log "7/8 — Сборка образов (5–15 минут, зависит от VPS)"
docker compose -f docker-compose-prod.yml --env-file .env.prod build --no-cache

log "Запуск стека..."
docker compose -f docker-compose-prod.yml --env-file .env.prod up -d

log "Ожидание готовности (90 секунд)..."
sleep 90

docker compose -f docker-compose-prod.yml ps

# ============================================================
# 8. Проверка
# ============================================================
log "8/8 — Проверка состояния"

HEALTH=$(curl -s http://localhost:8080/actuator/health 2>/dev/null || echo "DOWN")
echo "Health: $HEALTH"

if echo "$HEALTH" | grep -q "UP"; then
    ok "Приложение работает"
else
    warn "Healthcheck не отвечает — смотри логи:"
    echo "  docker compose -f docker-compose-prod.yml logs app | tail -50"
fi

# ============================================================
# Cron для бэкапов
# ============================================================
log "Настройка cron для бэкапов"
CRON_FILE=/etc/cron.d/afina-backup
cat > "$CRON_FILE" <<CRON
# Бэкап Афины каждый день в 3:00
0 3 * * * root cd $(pwd) && ./backup-prod.sh >> logs/backup.log 2>&1
CRON
chmod 644 "$CRON_FILE"
ok "Cron настроен: $CRON_FILE"

# ============================================================
# Финал
# ============================================================
echo ""
echo -e "${GREEN}════════════════════════════════════════════════${NC}"
echo -e "${GREEN}✅ Развёртывание завершено!${NC}"
echo -e "${GREEN}════════════════════════════════════════════════${NC}"
echo ""
echo "Адрес:         $(grep APP_BASE_URL .env.prod | cut -d'=' -f2)"
echo "Логи app:      docker compose -f docker-compose-prod.yml logs -f app"
echo "Логи caddy:    docker compose -f docker-compose-prod.yml logs -f caddy"
echo "Бэкапы:        ./backup-prod.sh"
echo "Восстановить:  ./restore-prod.sh backups/afina_*.sql.gz"
echo ""
echo "Проверь, что DNS-запись домена указывает на этот сервер."
echo "Caddy автоматически получит HTTPS-сертификат при первом запросе."
echo ""
