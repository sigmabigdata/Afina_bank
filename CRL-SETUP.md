# Управление CRL в Афине

Инструкция по работе со списками отзыва сертификатов (CRL).

---

## Что такое CRL и зачем

---

## Что такое CRL и зачем

CRL — Certificate Revocation List, список отозванных сертификатов,
публикуемый удостоверяющим центром (УЦ).

CRL — Certificate Revocation List, список отозванных сертификатов,
публикуемый удостоверяющим центром (УЦ).

При проверке УКЭП-подписи сервер обязан убедиться, что:

1. Сертификат подписанта не отозван (не в CRL)
2. Цепочка сертификатов ведёт до доверенного корня УЦ
3. Сертификат не истёк

Если CRL нет в хранилище — JCSP/RevCheck попытается скачать его
самостоятельно по URL из сертификата (см. системные свойства в
`CryptoProInitializer`). Но в корпоративных сетях и на macOS это
часто не работает. Поэтому CRL складываются в отдельный каталог.

---

## Где хранятся CRL

| Где | Путь |
|---|---|
| На сервере (хост) | `/opt/afina/crls/` |
| В контейнере | `/app/crls/` (read-only) |
| В репозитории | `crls/` |

Файлы: `*.crl`. В БД не хранятся.

---

## Как добавить CRL нового УЦ

### 1. Получить URL из сертификата клиента

Скачай у клиента его `.cer` файл (публичная часть). Затем:

    ./extract-crl-urls.sh /path/to/client.cer

Скрипт выведет:

    === CRL Distribution Points ===
    http://crl.myuc.ru/root.crl
    http://crl2.myuc.ru/root.crl

Все URL — альтернативные точки. Достаточно одной рабочей.

### 2. Добавить URL в crl-sources.conf

Открой `crl-sources.conf`, добавь строку:

    myuc_2026.crl|http://crl.myuc.ru/root.crl

Формат: `<имя_файла>|<URL>`. Имя — произвольное, но с `.crl` на конце.

### 3. Скачать CRL

На сервере:

    cd /opt/afina
    ./update-crls.sh

Скрипт:

- Скачает все URL из `crl-sources.conf`
- Сравнит с существующими — не перезапишет идентичные
- Запишет новые в `crls/`

Проверить, что скачался валидный CRL:

    openssl crl -in crls/myuc_2026.crl -noout -lastupdate -nextupdate

Должны быть обе даты. Если ошибка — файл битый, URL неверный.

### 4. Приложение подхватит автоматически

`SignatureService` читает **все** `*.crl` из `/app/crls/` при каждой
проверке подписи. Перезапуск приложения не требуется.

Проверить, что контейнер видит файл:

    docker exec afina-app ls -la /app/crls/

---

## Автообновление

Cron-задача `/etc/cron.d/afina-crl`:

    0 */6 * * * root cd /opt/afina && ./update-crls.sh >> /opt/afina/logs/crl-update.log 2>&1

Каждые 6 часов. Логи — в `/opt/afina/logs/crl-update.log`.

Проверить расписание:

    cat /etc/cron.d/afina-crl

Посмотреть последний запуск:

    tail -30 /opt/afina/logs/crl-update.log

---

## Ручная загрузка CRL

Если URL не качается (закрытый портал УЦ, нужна авторизация):

С MacBook:

    scp ~/Downloads/myuc.crl afina-vps:/opt/afina/crls/

На сервере:

    ls -la /opt/afina/crls/
    openssl crl -in /opt/afina/crls/myuc.crl -noout -lastupdate -nextupdate

Файл подхватится при следующей проверке — перезапуск не нужен.

---

## Диагностика

### Подпись не проходит, в логах `Could not determine revocation status`

Причина: нет CRL для этого УЦ.

Решение:

1. Проверить `/opt/afina/crls/` — есть ли там нужный CRL
2. Получить URL из сертификата клиента (`./extract-crl-urls.sh`)
3. Добавить в `crl-sources.conf`, запустить `./update-crls.sh`
4. Перезапустить app: `docker compose ... restart app`

### CRL старый (`nextUpdate` в прошлом)

УЦ перестал публиковать CRL по этому URL. Запросить актуальный у
клиента или у УЦ.

### Контейнер не видит файл в `/app/crls/`

Проверить монтирование:

    docker inspect afina-app | grep -A3 "Source.*crls"

Проверить, что файл на хосте:

    ls -la /opt/afina/crls/

Если файл есть на хосте, но нет в контейнере — перезапустить app.

---

## Мониторинг

`nextUpdate` каждого CRL — самая важная метрика. Если он в прошлом:

- CRL устарел
- Проверка подписи может ложно срабатывать
- Нужно срочно обновить

Проверить все CRL разом:

    cd /opt/afina
    for f in crls/*.crl; do
        [ -f "$f" ] || continue
        echo "=== $f ==="
        openssl crl -in "$f" -noout -lastupdate -nextupdate 2>&1
        echo ""
    done

Идея на будущее: добавить в `/admin/system` дашборд со статусом каждого
CRL (актуален/просрочен), алерт при `nextUpdate` < now.

---

## Известные УЦ и их CRL

| УЦ | Где взять URL |
|---|---|
| Контур | Из сертификата клиента |
| ФНС (ГУЦ) | Из сертификата клиента |
| Тензор | Из сертификата клиента |
| Ростелеком | Из сертификата клиента |

Универсальное правило: **никогда не угадывать URL**, всегда брать из
сертификата клиента.

---

## Ссылки

- RFC 5280 (CRL): https://datatracker.ietf.org/doc/html/rfc5280
- КриптоПро RevCheck: https://www.cryptopro.ru/products/cades
