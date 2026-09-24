#!/usr/bin/env bash
# Аварийная остановка — если Ctrl+C не сработал или терминал закрылся.
cd "$(dirname "$0")"
lsof -ti :8080 2>/dev/null | xargs kill -9 2>/dev/null || true
pkill -f "mvn spring-boot:run" 2>/dev/null || true
pkill -f "UkepSignApplication" 2>/dev/null || true
echo "▶ Остановлено."
