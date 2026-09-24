#!/usr/bin/env bash
cd "$(dirname "$0")"
echo "▶ Следим за аудит-логом (Ctrl+C — выход)"
echo ""
docker exec -it afina-postgres-dev sh -c \
  'tail -f /var/lib/postgresql/data/pg_log/postgresql-$(date +%Y-%m-%d).log' \
  | grep --line-buffered "AUDIT"
