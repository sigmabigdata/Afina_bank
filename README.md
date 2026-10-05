# Афина — Сервис подписания документов УКЭП

Веб-сервис для подписания документов усиленной квалифицированной
электронной подписью (УКЭП) через плагин КриптоПро.

---

## Содержание

1. [Возможности](#возможности)
2. [Стек](#стек)
3. [Требования](#требования)
4. [Развёртывание на VPS](#развёртывание-на-vps)
5. [Настройка после первого запуска](#настройка-после-первого-запуска)
6. [Обслуживание](#обслуживание)
7. [Настройка почты](#настройка-почты)
8. [Замена логотипа](#замена-логотипа)
9. [Типичные проблемы](#типичные-проблемы)
10. [Инструкция для клиента](#инструкция-для-клиента)
11. [Дополнительная документация](#дополнительная-документация)

---

## Возможности

- **Клиенты** входят по одноразовой ссылке (magic-link) на email
- **Администраторы** входят по УКЭП-сертификату (challenge-response + подпись)
- Загрузка и просмотр документов (PDF, DOCX, изображения и т.п.)
- Подписание документов УКЭП (CAdES-BES detached)
- Несколько подписей на один документ
- Админ-панель: управление клиентами, документами, бэкапами, система
- **Шифрование файлов на диске**: AES-256-GCM
- pgAudit для аудита действий в БД
- Автоматические бэкапы БД и файлов
- HTTPS через Caddy + Let's Encrypt

---

## Стек

| Слой | Технология |
|---|---|
| Backend | Java 17, Spring Boot 3.2.5 |
| БД | PostgreSQL 16 + Flyway + pgAudit + pg_stat_statements |
| Криптография | КриптоПро CSP 5.0 + JCSP/JCP, BouncyCastle |
| Frontend | Thymeleaf, ванильный JS, плагин CryptoPro CAdES |
| Шифрование файлов | AES-256-GCM (JCE, SunJCE provider) |
| Инфраструктура | Docker Compose, Caddy 2 |
| ОС | Ubuntu 22.04 / 24.04 LTS, x86_64 |

---

## Требования

### Сервер

- **VPS** с Ubuntu 22.04 или 24.04 LTS, **x86_64** (ARM не подойдёт из-за CryptoPro)
- Минимум: 2 vCPU, 4 GB RAM, 40 GB SSD
- root или sudo
- Открытые порты: 22 (SSH), 80 (HTTP), 443 (HTTPS)
- **Домен** с A-записью на IP сервера (например `afina.example.ru`)

### Что должно быть в репозитории

---

## Развёртывание на VPS

### 1. Подготовка сервера

    ssh root@<IP-сервера>
    apt update && apt upgrade -y
    curl -fsSL https://get.docker.com | sh
    systemctl enable --now docker

### 2. Клонировать репозиторий

    mkdir -p /opt/afina
    cd /opt/afina
    git clone git@github.com:sigmabigdata/Afina_bank.git .

### 3. Разложить секреты

Скопировать на сервер (с локальной машины):

    scp .env.prod          afina-vps:/opt/afina/
    scp admins.env         afina-vps:/opt/afina/
    scp kontur-q-2025.crl  afina-vps:/opt/afina/
    scp -r certs/          afina-vps:/opt/afina/
    scp -r cryptopro-dist/ afina-vps:/opt/afina/

Заполнить `.env.prod` на сервере (`nano .env.prod`):

    APP_DOMAIN=afina.example.ru
    APP_BASE_URL=https://afina.example.ru
    SECURE_COOKIES=true

    DB_NAME=afina_db
    DB_SUPERUSER=postgres
    DB_SUPERUSER_PASSWORD=<strong-random>

    DB_USER=afina_app
    DB_PASSWORD=<strong-random>

    DB_MIGRATOR_USER=afina_migrator
    DB_MIGRATOR_PASSWORD=<strong-random>

    DB_AUDITOR_USER=afina_auditor
    DB_AUDITOR_PASSWORD=<strong-random>

    ADMIN_IP_WHITELIST=127.0.0.1,::1,172.0.0.0/8

    MAIL_MODE=smtp
    MAIL_HOST=mail.example.ru
    MAIL_PORT=465
    MAIL_USERNAME=noreply@example.ru
    MAIL_PASSWORD=<smtp-password>
    MAIL_FROM=noreply@example.ru

    CRYPTO_PRO_ENABLED=true

Права: `chmod 600 .env.prod admins.env`

### 4. Сгенерировать ключ шифрования

**КРИТИЧНО:** без этого файла приложение не запустится.

    cd /opt/afina
    mkdir -p secrets
    chmod 700 secrets
    openssl rand -base64 32 > secrets/file.key
    chown -R 999:999 secrets
    chmod 600 secrets/file.key
    cat secrets/file.key

Скопировать ключ на локальную машину:

    mkdir -p ~/afina-secrets
    scp afina-vps:/opt/afina/secrets/file.key ~/afina-secrets/file.key.$(date +%Y%m%d)

Положить копию в менеджер паролей. Без ключа зашифрованные файлы
восстановить нельзя.

### 5. Настроить DNS

    A    afina.example.ru       → <IP-сервера>
    A    www.afina.example.ru   → <IP-сервера>

Проверить: `dig +short afina.example.ru`

### 6. Запустить развёртывание

    cd /opt/afina
    sudo ./deploy.sh

`deploy.sh` выполняет:

1. Проверки окружения
2. `apt update && upgrade`
3. Установка Docker
4. Firewall (UFW) и fail2ban
5. Параметры ядра
6. Создание каталогов (`storage/`, `logs/`, `backups/`)
7. Сборка образа приложения
8. Запуск стека
9. Healthcheck
10. Cron для ежедневного бэкапа (3:00)

Продолжительность: 5–15 минут.

---

## Настройка после первого запуска

### 1. Проверить миграции Flyway

    docker exec afina-postgres psql -U postgres -d afina_db -c \
      "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"

Ожидаемые версии:

| V | Описание |
|---|---|
| 1 | init |
| 2 | grants |
| 3 | ownership |
| 4 | login token used at |
| 5 | document signatures |
| 6 | file encryption |

У всех `success = t`.

### 2. Настроить pgAudit

    cd /opt/afina
    sudo ./setup-db.sh

### 3. Healthcheck

    curl -s http://localhost:8080/actuator/health
    # {"status":"UP"}

    curl -sI https://afina.example.ru/actuator/health | head -1
    # HTTP/2 200

### 4. Проверить ключ шифрования

    docker compose -f docker-compose-prod.yml --env-file .env.prod logs app | grep -i FileEncryptor
    # FileEncryptor: ключ загружен из /opt/afina/secrets/file.key

### 5. Cron бэкапа

    cat /etc/cron.d/afina-backup

### 6. Firewall и fail2ban

    ufw status
    systemctl status fail2ban
    fail2ban-client status sshd

---

## Обслуживание

### Бэкап (ручной)

    cd /opt/afina
    ./backup-prod.sh

Создаёт в `backups/`:

- `afina_YYYYMMDD_HHMMSS.sql.gz` — дамп БД
- `storage_YYYYMMDD_HHMMSS.tar.gz` — архив `storage/documents/` (уже зашифрован)

Ротация: удаляются файлы старше 30 дней.

### Восстановление из бэкапа

**Внимание:** заменяет все данные. Перед восстановлением автоматически
создаётся safety-бэкап.

    cd /opt/afina
    docker compose -f docker-compose-prod.yml --env-file .env.prod stop app

    # БД
    gunzip -c backups/afina_YYYYMMDD_HHMMSS.sql.gz | \
      docker exec -i afina-postgres psql -U postgres -d afina_db

    # Владельцы и права
    docker exec -i afina-postgres psql -U postgres -d afina_db <<'SQL'
    DO $$ DECLARE r RECORD;
    BEGIN
        FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='public' LOOP
            EXECUTE 'ALTER TABLE public.' || quote_ident(r.tablename) || ' OWNER TO afina_migrator';
        END LOOP;
        FOR r IN SELECT sequencename FROM pg_sequences WHERE schemaname='public' LOOP
            EXECUTE 'ALTER SEQUENCE public.' || quote_ident(r.sequencename) || ' OWNER TO afina_migrator';
        END LOOP;
    END $$;
    SQL

    # Файлы
    tar xzf backups/storage_YYYYMMDD_HHMMSS.tar.gz -C /opt/afina

    # Запуск
    docker compose -f docker-compose-prod.yml --env-file .env.prod up -d app

В UI админки (`/admin/system`) есть кнопки «Восстановить» и «Удалить»
рядом с каждым бэкапом.

### Обновление приложения

    cd /opt/afina
    git pull
    docker compose -f docker-compose-prod.yml --env-file .env.prod build --no-cache app
    docker compose -f docker-compose-prod.yml --env-file .env.prod up -d app
    sleep 30
    docker compose -f docker-compose-prod.yml --env-file .env.prod ps

Flyway применит новые миграции автоматически.

### Просмотр логов

    # Приложение (стрим)
    docker compose -f docker-compose-prod.yml --env-file .env.prod logs -f app

    # Только ошибки
    docker compose -f docker-compose-prod.yml --env-file .env.prod logs app | grep -iE 'ERROR|Exception'

    # PostgreSQL
    docker exec afina-postgres tail -50 /var/lib/postgresql/data/pg_log/postgresql-$(date +%F).log

    # pgAudit
    docker exec afina-postgres sh -c \
      'tail -f /var/lib/postgresql/data/pg_log/postgresql-$(date +%F).log' | grep AUDIT

### Перезапуск

    docker compose -f docker-compose-prod.yml --env-file .env.prod restart app

Или через UI: `/admin/system` → «Перезапустить приложение».

### Добавление администратора

    cd /opt/afina
    nano admins.env
    # Формат: ФИО|СНИЛС
    # Пример: Иванов Иван Иванович|12345678901

    docker compose -f docker-compose-prod.yml --env-file .env.prod restart app

Проверка:

    docker compose -f docker-compose-prod.yml --env-file .env.prod logs app | grep -A5 "Администраторы"

Запись появится в БД только **после первого входа** через `/admin/login`.

### Ротация ключа шифрования

Полная процедура — см. `TECHNICAL.md`, раздел «Ротация ключа».

---

## Настройка почты

Письма с magic-link отправляются через SMTP. Параметры — в `.env.prod`:

    MAIL_MODE=smtp
    MAIL_HOST=mail.example.ru
    MAIL_PORT=465
    MAIL_USERNAME=noreply@example.ru
    MAIL_PASSWORD=<пароль>
    MAIL_FROM=noreply@example.ru
    MAIL_FROM_NAME=Афина

- `MAIL_MODE=log` — письма пишутся в лог приложения (для dev)
- `MAIL_MODE=smtp` — реальная отправка
- `MAIL_FROM_NAME` — отображаемое имя отправителя (по умолчанию «Афина»)
- Тема письма: `Афина · Ссылка для входа`

После изменения `.env.prod` — перезапустить app:

    docker compose -f docker-compose-prod.yml --env-file .env.prod restart app

## Замена логотипа

Логотип лежит в `src/main/resources/static/img/`:

| Файл | Назначение |
|---|---|
| `logo.png` | Основной (636×120, прозрачный фон) |
| `logo-2x.png` | Retina-версия для HiDPI |

**Заменить логотип:**

1. Подготовить PNG-файл с прозрачным фоном
2. Уменьшить до высоты 120 px (ширина пропорционально)
3. Положить в `src/main/resources/static/img/logo.png`
4. Rebuild + restart:

       docker compose -f docker-compose-prod.yml --env-file .env.prod build --no-cache app
       docker compose -f docker-compose-prod.yml --env-file .env.prod up -d app

**Где отображается:**

- Шапка клиентского кабинета (`/dashboard`)
- Шапка админки (`/admin/*`) — инвертируется через CSS-фильтр
- Страницы логина (`/login`, `/admin/login`, `/login-sent`, `/error`)

**CSS-классы:**

- `.brand-logo` — логотип в шапке (40 px)
- `.admin-header .brand-logo` — в тёмной шапке (38 px, инверсия)
- `.auth-logo-img` — на страницах логина (72 px)

## Типичные проблемы

### Приложение не стартует: «Ключ шифрования не найден»

Симптом в логах:

    java.lang.IllegalStateException: Ключ шифрования не найден: /opt/afina/secrets/file.key

Причина: контейнер (uid=999) не может прочитать файл.

Решение:

    cd /opt/afina
    chown -R 999:999 secrets
    chmod 700 secrets
    chmod 600 secrets/file.key
    docker compose -f docker-compose-prod.yml --env-file .env.prod restart app

### Caddy отдаёт 502 Bad Gateway

Причина: приложение не запустилось или ещё стартует.

Проверка:

    docker compose -f docker-compose-prod.yml --env-file .env.prod ps
    docker compose -f docker-compose-prod.yml --env-file .env.prod logs app | tail -50

Ожидание: после `Started UkepSignApplication` (~15 секунд) 502 пропадёт.

### Caddy не получает сертификат Let's Encrypt

Причины:

- DNS не указывает на IP сервера (`dig +short afina.example.ru`)
- Порт 80 закрыт (нужен для ACME-челленджа)
- Rate limit LE (много выпусков за короткое время)

Проверка:

    docker compose -f docker-compose-prod.yml --env-file .env.prod logs caddy | tail -30

### Миграция legacy-файлов не работает

Симптом: 0 зашифровано.

Причина: app не остановлен, или неверный профиль.

Решение:

    docker compose -f docker-compose-prod.yml --env-file .env.prod stop app
    docker compose -f docker-compose-prod.yml --env-file .env.prod run --rm \
      -e SPRING_PROFILES_ACTIVE=prod,migrate app
    docker compose -f docker-compose-prod.yml --env-file .env.prod up -d app

### Файлы в storage/ остались без .enc

Причина: код старой версии (до V6).

Решение: убедиться, что `git log` показывает коммит `feat(crypto)...`,
затем:

    docker compose -f docker-compose-prod.yml --env-file .env.prod build --no-cache app
    docker compose -f docker-compose-prod.yml --env-file .env.prod up -d app

---

## Инструкция для клиента

Клиенту (пользователю УКЭП) нужно настроить рабочее место.

### Требования

- Windows 10/11, macOS 12+, или Linux (Chromium-Gost)
- Chrome / Yandex Browser / Chromium-Gost
- Токен Рутокен ЭЦП 2.0 / 3.0 или Etoken

### Установка

1. **Драйвер токена**
   - Рутокен: https://www.rutoken.ru/support/download/
   - Etoken: https://www.safenet-inc.com/

2. **КриптоПро CSP 5.0** — https://www.cryptopro.ru/products/csp
   - Установить от имени администратора
   - Ввести лицензию

3. **Плагин КриптоПро для браузера** — https://www.cryptopro.ru/products/cades/plugin
   - Установить
   - Включить расширение в браузере (`chrome://extensions`)
   - Дать расширению доступ «на всех сайтах»

4. **Личный сертификат** — установить в хранилище «Личные»:
   - Двойной клик по файлу `.cer` → «Установить сертификат»
   - Или через КриптоПро → «Сертификаты» → «Установить»

### Вход в систему

1. Открыть `https://afina.example.ru/login`
2. Ввести email → получить ссылку на почту
3. Перейти по ссылке → попасть в личный кабинет

### Подписание документа

1. Загрузить документ (кнопка «Загрузить»)
2. Нажать кнопку подписи рядом с документом
3. Выбрать сертификат
4. Ввести PIN-код токена
5. Дождаться подтверждения

### Проблема: «Истекло время ожидания загрузки плагина»

Причины:

- Расширение плагина не установлено или выключено
- Расширению не дан доступ «на всех сайтах»
- Корпоративный прокси блокирует `cryptopro.ru`

Решение:

1. Проверить `chrome://extensions` — расширение CryptoPro включено
2. Попробовать другой браузер (Chrome / Yandex / Chromium-Gost)
3. Обратиться к администратору

### Проблема: «Insert carrier ... to open container»

Причина: sandbox браузера блокирует доступ к токену.

Решение — запустить браузер с флагом `--no-sandbox`:

Windows:

    "C:\Program Files\Google\Chrome\Application\chrome.exe" --no-sandbox

macOS:

    /Applications/Yandex.app/Contents/MacOS/Yandex --no-sandbox

---

## Лицензия

Проприетарная. Все права защищены.

КриптоПро CSP — лицензия КриптоПро (приобретается отдельно).

---

## Контакты

- Разработка: sigmabigdata
- Репозиторий: https://github.com/sigmabigdata/Afina_bank

---

## Дополнительная документация

- [CRL-SETUP.md](CRL-SETUP.md) — управление CRL (списки отзыва сертификатов)
- [TECHNICAL.md](TECHNICAL.md) — техническая документация (архитектура, API)

---

## Свой SSL-сертификат (TLS_MODE=custom)

По умолчанию Caddy сам выпускает Let's Encrypt. Если у вас уже есть
свой сертификат (Wildcard от Comodo, корпоративный CA, самоподписанный
для внутренней сети) — можно переключиться на режим `custom`.

### Как переключиться

1. **Положить сертификаты** в `/opt/afina/caddy-certs/`:

       fullchain.pem   # полная цепочка: сертификат + промежуточные CA
       privkey.pem     # приватный ключ

   Права:

       chmod 644 caddy-certs/fullchain.pem
       chmod 600 caddy-certs/privkey.pem
       chown root:root caddy-certs/*.pem

2. **В `.env.prod`** изменить:

       TLS_MODE=custom

3. **Перезапустить Caddy:**

       cd /opt/afina
       docker compose -f docker-compose-prod.yml --env-file .env.prod up -d caddy

4. **Проверить:**

       curl -sI https://ваш-домен/actuator/health | head -1
       # HTTP/2 200

       ./check-ssl.sh
       # Domain:    ваш-домен
       # TLS mode:  custom
       # Expires:   ...
       # Days left: ...
       # ✓ ок

### Что важно

- **Caddy не обновляет ваш сертификат.** Когда он истечёт — сайт
  упадёт. `check-ssl.sh` предупредит за 14 дней.
- **Let's Encrypt полностью отключается** для этого домена, как только
  в блоке появилась директива `tls`. Автоматический ACME-челлендж
  запускаться не будет.
- **Порт 80 всё ещё нужен** для редиректа HTTP→HTTPS и ACME (если
  когда-то захотите вернуться к LE).
- **Приватный ключ** `privkey.pem` — критичный файл. Бэкапьте его
  отдельно (1Password, сейф). Утечка ключа = компрометация SSL.

### Проверка валидности цепочки

Если браузер жалуется на неполную цепочку:

    openssl s_client -connect ваш-домен:443 -servername ваш-домен
    # В конце должно быть "Verify return code: 0 (ok)"

Если `unable to get local issuer certificate` — в `fullchain.pem`
не хватает промежуточных сертификатов. Соберите полную цепочку:

    cat server.crt intermediate.crt > fullchain.pem

### Возврат на Let's Encrypt

1. В `.env.prod`: `TLS_MODE=letsencrypt`
2. Перезапустить:

       docker compose -f docker-compose-prod.yml --env-file .env.prod up -d caddy

Caddy снова начнёт автоматически выпускать сертификаты.

### Обновление сертификата (для custom)

Когда придёт время обновлять (например, раз в год):

1. Получить новый `fullchain.pem` и `privkey.pem` от CA
2. Заменить файлы в `caddy-certs/`
3. Проверить права (`600` на ключ)
4. Перезагрузить Caddy **без перезапуска контейнера**:

       docker compose -f docker-compose-prod.yml --env-file .env.prod \
         exec caddy caddy reload --config /etc/caddy/Caddyfile

Или (если reload не сработал):

       docker compose -f docker-compose-prod.yml --env-file .env.prod restart caddy

5. Проверить:

       ./check-ssl.sh
       curl -sI https://ваш-домен/actuator/health | head -1

### Что НЕ надо менять

- Caddyfile — он шаблонный, ничего править в нём не нужно
- docker-compose — уже настроен (volume `./caddy-certs:/certs:ro`)
- Приложение — оно за Caddy и не знает про SSL
