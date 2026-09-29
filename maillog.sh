#!/usr/bin/env bash
cd "$(dirname "$0")"
echo "▶ Просмотр логов отправки почты (Ctrl+C — выход)"
echo ""
LOG_FILE="logs/app.log"
if [ ! -f "$LOG_FILE" ]; then
  echo -e "\033[0;31m❌ Файл logs/app.log не найден. Запусти ./run.sh\033[0m"
  exit 1
fi
tail -f "$LOG_FILE" | grep --line-buffered -iE 'DEV-MAIL|FALLBACK-MAIL|Login link email|MailSendException|SMTP|mail'
