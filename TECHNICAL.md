# Афина — Техническая документация

Документ для ИТ-специалистов: архитектура, модель безопасности,
схема данных, API, эксплуатация.

---

## Содержание

1. [Обзор архитектуры](#обзор-архитектуры)
2. [Компоненты](#компоненты)
3. [Потоки данных](#потоки-данных)
4. [Аутентификация](#аутентификация)
5. [Шифрование данных](#шифрование-данных)
6. [Модель ролей БД](#модель-ролей-бд)
7. [Схема БД](#схема-бд)
8. [Криптография УКЭП](#криптография-укэп)
9. [Управление CRL](#управление-crl)
10. [Rate limiting](#rate-limiting)
11. [Аудит](#аудит)
12. [Мониторинг](#мониторинг)
13. [API](#api)
14. [Эксплуатация](#эксплуатация)
15. [Известные ограничения](#известные-ограничения)

---

## Обзор архитектуры

    ┌─────────────┐         ┌──────────────┐        ┌──────────────────┐
    │  Браузер    │  HTTPS  │    Caddy     │  HTTP  │  Spring Boot app │
    │ (клиент или │ ──────► │  (TLS+ACME)  │ ─────► │  (порт 8080)     │
    │  админ)     │         │              │        │                  │
    └──────┬──────┘         └──────────────┘        └────────┬─────────┘
           │                                                  │
           │ плагин КриптоПро                                 │ JDBC
           │ (подпись локально                                │
           │  на токене)                                      ▼
           │                                       ┌──────────────────┐
           ▼                                       │   PostgreSQL 16  │
    ┌─────────────┐                                │   + pgAudit      │
    │   Токен     │                                │   + pg_stat_stmt │
    │ (Рутокен)   │                                └──────────────────┘
    └─────────────┘                                         │
                                                            │
                                              ┌─────────────▼────────────┐
                                              │ storage/documents/       │
                                              │ AES-256-GCM (.enc)       │
                                              └──────────────────────────┘

**Ключевые принципы:**

- Приватный ключ УКЭП **никогда не покидает токен** клиента
- Сервер видит только публичный сертификат и подпись
- Файлы на диске зашифрованы AES-256-GCM
- PII в БД (email, телефон, ФИО-в-документах) зашифрованы отдельным ключом
- Caddy сам получает и обновляет сертификаты Let's Encrypt (или клиентские)
- Всё в Docker Compose: app + postgres + caddy

---

## Компоненты

### 1. Приложение (Spring Boot)

Контейнер `afina-app`, порт 8080 (наружу не публикуется).
Читает `.env.prod`, монтирует `storage/`, `logs/`, `backups/`, `certs/`, `crls/`, `secrets/`.

Ключевые модули:

| Модуль | Назначение |
|---|---|
| `SecurityConfig` | Две цепочки фильтров: admin (IP whitelist) и user |
| `RateLimitFilter` | Rate-limit до Spring Security |
| `AdminCertAuthController` | Вход админов через УКЭП (challenge + подпись) |
| `AuthController` | Вход клиентов через magic-link |
| `SignatureVerifier` | Полная проверка CAdES-BES (BouncyCastle + chain + CRL) |
| `CrlDownloader` | Автозагрузка CRL по CDP из сертификатов |
| `DocumentService` | Загрузка/скачивание/шифрование файлов |
| `FileEncryptor` | AES-256-GCM для файлов |
| `PiiEncryptor` | AES-256-GCM для PII (email, phone, ФИО) |
| `PiiStringConverter` | JPA `@Convert` для прозрачного шифрования |
| `AuditService` | Запись событий в `audit_events` |
| `MonitoringService` | Scheduled health-check + алерты |
| `BackupService` | pg_dump + psql restore |
| `SettingsService` | Key-value настройки (`app_settings`) |
| `KeysService` | Информация о ключах шифрования |

### 2. PostgreSQL 16

Контейнер `afina-postgres`, порт 5432 (наружу не публикуется).
Образ `afina-postgres:16-pgaudit` — PostgreSQL + pgAudit.

Расширения:
- `pg_stat_statements` — мониторинг запросов
- `pgaudit` — аудит DDL/DML
- `pgcrypto` — криптография
- `uuid-ossp` — UUID-генерация

### 3. Caddy 2

Контейнер `afina-caddy`. Порты настраиваются через `HTTP_PORT`/`HTTPS_PORT` (по умолчанию 80/443).
Reverse-proxy с автоматическим TLS.

`Caddyfile` (шаблонный):

    {$APP_DOMAIN} {
        encode gzip
        {$TLS_DIRECTIVE}
        reverse_proxy app:8080
    }

    www.{$APP_DOMAIN} {
        redir https://{$APP_DOMAIN}{uri} permanent
    }

`TLS_DIRECTIVE`:
- Пусто → Let's Encrypt (ACME автоматически)
- `tls /certs/fullchain.pem /certs/privkey.pem` → свой сертификат

### 4. Хранилище

| Путь | Содержимое | Формат |
|---|---|---|
| `/opt/afina/storage/documents/` | Файлы документов | `<uuid>_<orig>.enc`, AES-256-GCM |
| `/opt/afina/secrets/file.key` | Ключ для файлов | base64, 32 байта |
| `/opt/afina/secrets/pii.key` | Ключ для PII | base64, 32 байта |
| `/opt/afina/certs/` | Truststore | X.509 .cer/.crt/.pem |
| `/opt/afina/crls/` | CRL | `auto-*.crl` + вручную |
| `/opt/afina/backups/` | Бэкапы | `.sql.gz` + `storage_*.tar.gz` |
| `/opt/afina/logs/` | Логи приложения | `app.log` |

---

## Потоки данных

### Загрузка документа

    1. Клиент → POST /documents/upload (multipart)
    2. DocumentController.upload()
    3. DocumentService.upload():
         a. file.getBytes() → plaintext (в памяти)
         b. FileEncryptor.encrypt(plaintext):
              - IV = secureRandom(12 байт)
              - AES-256-GCM(key, IV, plaintext) → ciphertext + tag
              - return (IV || ciphertext)
         c. Files.write(storage/<uuid>_<orig>.enc, IV||ct)
         d. sha256 = SHA-256(plaintext)
         e. originalName → PiiStringConverter → encrypted
         f. INSERT INTO documents
    4. audit.documentUpload()
    5. Redirect /dashboard

### Скачивание документа

    1. Клиент → GET /documents/{id}/download
    2. DocumentService.getOwned(id, user) — проверка владельца
    3. DocumentService.getBytes(doc):
         a. Files.readAllBytes(storage/<stored_name>)
         b. FileEncryptor.decrypt(raw): IV = raw[0..11], AES-GCM
    4. ResponseEntity<Resource> с plaintext

### Подписание документа (клиент)

    1. JS: fetch /documents/{id}/view → arrayBuffer → base64
    2. JS: cadesplugin.signBase64(thumbprint, contentBase64)
         — плагин подписывает ЛОКАЛЬНО на токене
    3. JS: POST /api/sign/accept {documentId, signatureBase64}
    4. SignApiController:
         - getOwned(id, user)
         - documentService.getBytes(doc) → plaintext
         - signatureVerifier.verifyDetached(plaintext, sig):
             • BouncyCastle: парсинг CAdES, проверка подписи
             • Chain building: leaf → intermediate → root
             • Проверка корня в truststore (/app/certs)
             • Проверка отзыва по CRL (/app/crls)
         - documentService.addSignature() → INSERT document_signatures
         - audit.signSuccess()
    5. 200 OK

### Вход администратора (УКЭП)

    1. GET /admin/login → HTML
    2. JS: GET /admin/challenge → {challenge: "<uuid>-<ts>"}
       (в памяти, TTL 5 мин, одноразовый)
    3. JS: signBase64(thumbprint, btoa(challenge))
    4. JS: POST /admin/cert-login {cn, snils, challenge, signatureBase64}
    5. AdminCertAuthController:
         - challenges.remove(challenge)
         - adminsFile.find(cn, snils) → 403 если нет
         - signatureVerifier.verifyDetached(challenge, signature)
         - extractCnFromLastSignature() == CN из admins.env
         - userService.upsertAdminByCert()
         - SecurityContextHolder → ADMIN_SECURITY_CONTEXT
         - audit.adminLoginSuccess()
    6. 200 OK

### Вход клиента (magic-link)

    1. POST /login {email}
    2. UserService.generateLoginLink(email, baseUrl):
         - findByEmailHash(pii.hash(email))
         - если нет или !enabled → return null (не палим)
         - token = UUID.randomUUID()
         - UPDATE login_token, login_token_expires = now+10h
    3. EmailService.sendLoginLink() → SMTP (или лог в dev)
    4. Клиент переходит → GET /login/confirm?token=...
    5. UserService.consumeLoginToken():
         - findByLoginToken
         - проверка expires
         - токен многоразовый до TTL (анти-prefetch Gmail)
         - UPDATE last_login_at, login_token_used_at
         - audit.loginSuccess()
    6. SecurityContextHolder → USER_SECURITY_CONTEXT
    7. Redirect /dashboard

---

## Аутентификация

### Два независимых контекста

В одном HTTP-сеансе могут одновременно жить две аутентификации:

| Контекст | Ключ в HttpSession | Кто |
|---|---|---|
| `USER_SECURITY_CONTEXT` | `USER_SECURITY_CONTEXT` | Клиент (magic-link) |
| `ADMIN_SECURITY_CONTEXT` | `ADMIN_SECURITY_CONTEXT` | Админ (УКЭП) |

Два `HttpSessionSecurityContextRepository` с разными `springSecurityContextKey`.
Логаут одного не затрагивает другого.

### Цепочки фильтров Spring Security

**`@Order(1)` — adminChain:**

- `securityMatcher("/admin/**", "/api/sign/admin/**")`
- `adminIpFilter` — пропускает только IP из `app.admin-ip-whitelist`
- `.anyRequest().hasRole("ADMIN")`
- AuthenticationEntryPoint: AJAX → 401 JSON, иначе redirect `/admin/login`
- AccessDeniedHandler: 403

**`@Order(2)` — userChain:**

- Всё остальное
- Открытые пути: `/`, `/login`, `/login/confirm`, `/error`, `/actuator/health`, `/actuator/info`, `/ping`, `/css/**`, `/js/**`, `/img/**`, `/favicon.ico`
- `.anyRequest().authenticated()`

**Rate limit — до обеих цепочек:** `RateLimitFilter` с `@Order(HIGHEST_PRECEDENCE)`.

CSRF отключён для обеих цепочек (cert-auth + IP whitelist для админов,
stateless magic-link для клиентов).

### Логин админа по УКЭП

Реализация — `AdminCertAuthController`.

**Защита от replay:**
- Одноразовый challenge в `ConcurrentHashMap` с TTL 5 минут
- `challenges.remove()` при первом использовании
- TTL-очистка при каждом новом запросе
- Challenge содержит timestamp: `<uuid>-<millis>`

**Проверка:**
1. Base64 → CMS/CAdES через BouncyCastle
2. Проверка подписи через JCSP (для ГОСТ) с fallback
3. Сверка CN из подписи с `admins.env`
4. `userService.upsertAdminByCert()` — синтетический email
   `<cn-translit>+<snils>@ukep.local`

**Что защищено:**
- Подделка CN/СНИЛС в POST — отсекается сравнением с `admins.env`
- Подмена IP — фильтр на уровне сервлета
- MITM при HTTPS — TLS 1.2/1.3 + HSTS от Caddy
- Replay challenge — одноразовый + TTL

**Что НЕ защищено:**
- Rate limiting на `/admin/challenge` — только общий (10/15мин)

### Логин клиента по magic-link

Реализация — `AuthController`, `UserService`.

**Токен:**
- `UUID.randomUUID().replace("-","")` — 32 hex
- TTL 10 часов
- Хранение в `users.login_token`
- **Многоразовый** в пределах TTL (компромисс против Gmail-prefetch)

**Что защищено:**
- Энумерация email — не палим существование (всегда 200 OK)
- `enabled=false` → токен не выпускается
- TTL 10 часов
- `last_login_at` фиксируется

**Что НЕ защищено:**
- Повторное использование токена до истечения TTL (компромисс)
- Rate limiting есть: 5/15мин на IP+email (см. раздел Rate limiting)

---

## Шифрование данных

### Файлы документов

- **Шифр:** AES-256-GCM (`AES/GCM/NoPadding`, SunJCE)
- **Ключ:** 32 байта, base64 в `/opt/afina/secrets/file.key`
- **IV:** 12 байт, случайные для каждого файла
- **AuthTag:** 16 байт (128 бит), автоматически от GCM
- **Формат на диске:** `[IV 12 байт][ciphertext][authTag 16 байт]`
- IV дублируется в БД (`documents.encryption_iv`, base64)

### PII в БД

| Поле | Колонка | Тип |
|---|---|---|
| `users.email` | `email_enc` | AES-256-GCM через `@Convert` |
| `users.email` | `email_hash` | SHA-256(lower(email)), unique |
| `users.phone` | `phone_enc` | AES-256-GCM |
| `users.phone` | `phone_hash` | SHA-256(digits), nullable |
| `users.full_name` | `full_name` | **plaintext** (для fuzzy-поиска) |
| `documents.original_name` | `original_name_enc` | AES-256-GCM |
| `documents.signer_subject` | `signer_subject_enc` | AES-256-GCM |
| `document_signatures.signer_subject` | `signer_subject_enc` | AES-256-GCM |

Реализация — `PiiStringConverter` (JPA `AttributeConverter<String, String>`).
`PiiEncryptor` — AES-256-GCM, ключ `/opt/afina/secrets/pii.key`.

**Особенность `decrypt()`:** если строка не похожа на base64 от нашего
формата (короткая или не парсится) — возвращается как есть. Это
legacy-fallback для строк, оставшихся в plaintext до миграции.

### Что защищает

| Угроза | Защита |
|---|---|
| Кража HDD / snapshot диска | ✅ Криптомусор без ключа |
| Утечка через хостинг | ✅ Только шифротекст |
| Утечка бэкапа БД | ✅ PII в бэкапе зашифрован |
| Root-компрометация на работающем сервере | ⚠️ Ключ доступен из `secrets/` |
| Fuzzy-поиск по email в админке | ❌ Только exact по hash |

Для защиты от root-компрометации нужен KMS (Vault, AWS KMS) — отдельная итерация.

### Жизненный цикл ключа

- **Создание:** `install.sh` или `openssl rand -base64 32`
- **Ротация:** не реализована (нет утилиты `RotateKey`)
- **Резервная копия:** `/admin/keys` → скачать ZIP
- **Компромисс:** потеря ключа = потеря данных

---

## Модель ролей БД

Три роли с разделением привилегий:

| Роль | Назначение | Права |
|---|---|---|
| `afina_migrator` | Flyway, владелец схемы | CREATE, ALTER, DROP, DML |
| `afina_app` | Приложение | SELECT, INSERT, UPDATE, DELETE |
| `afina_auditor` | Аудит | SELECT |

Superuser (`postgres`) — только для restore и init-скриптов.

### Default privileges

Для будущих объектов, созданных `afina_migrator`:

    ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO afina_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
        GRANT SELECT ON TABLES TO afina_auditor;
    ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO afina_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE afina_migrator IN SCHEMA public
        GRANT SELECT ON SEQUENCES TO afina_auditor;

### Что это даёт

- SQL-инъекция в приложении не даст DROP TABLE — `afina_app` не имеет DDL
- Утечка пароля приложения не откроет запись — только CRUD по данным
- Аудитор видит всё, но не может ничего изменить
- Компрометация `afina_migrator` опаснее, но пароль используется только Flyway при старте

---

## Схема БД

### users

| Колонка | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| email_enc | TEXT | Email, AES-256-GCM |
| email_hash | VARCHAR(64) | SHA-256, unique |
| phone_enc | TEXT | Телефон, AES-256-GCM |
| phone_hash | VARCHAR(64) | SHA-256 digits, nullable |
| full_name | VARCHAR(255) | ФИО, plaintext |
| role | VARCHAR(30) | ROLE_USER / ROLE_ADMIN |
| enabled | BOOLEAN | false = заблокирован |
| login_token | VARCHAR(128) | magic-link |
| login_token_expires | TIMESTAMPTZ | TTL 10ч |
| login_token_used_at | TIMESTAMPTZ | первое использование |
| last_login_at | TIMESTAMPTZ | |
| created_at | TIMESTAMPTZ | |

Индексы: `email_hash` (unique), `phone_hash`, `login_token`, `role`.

### documents

| Колонка | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| original_name_enc | TEXT | AES-256-GCM |
| stored_name | VARCHAR(500) | `<uuid>_<sanitized>.enc` |
| content_type | VARCHAR(200) | MIME |
| size | BIGINT | размер оригинала |
| file_sha256 | VARCHAR(64) | sha256 оригинала |
| signature_base64 | TEXT | legacy, дублируется в document_signatures |
| signed | BOOLEAN | есть хотя бы одна подпись |
| signed_at | TIMESTAMPTZ | |
| signer_subject_enc | VARCHAR(1000) | CN первого подписанта |
| signer_serial | VARCHAR(100) | |
| uploaded_at | TIMESTAMPTZ | |
| owner_id | BIGINT FK → users(id) ON DELETE CASCADE | |
| encryption_iv | VARCHAR(32) | base64 IV |
| key_version | VARCHAR(20) FK → file_keys(version) | |
| encrypted | BOOLEAN | |

Индексы: `owner_id`, `signed`, `uploaded_at DESC`.

### document_signatures

| Колонка | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| document_id | BIGINT FK → documents(id) ON DELETE CASCADE | |
| signature_base64 | TEXT | CAdES-BES |
| signed_at | TIMESTAMPTZ | |
| signer_subject_enc | VARCHAR(1000) | CN, AES-GCM |
| signer_serial | VARCHAR(100) | |
| signer_user_id | BIGINT FK → users(id) ON DELETE SET NULL | |

**Одна запись = одна подпись. Документ может иметь N подписей.**

### file_keys

| Колонка | Тип |
|---|---|
| version | VARCHAR(20) UNIQUE, `v1` |
| algorithm | VARCHAR(50), `AES-256-GCM` |
| active | BOOLEAN |

### app_settings

| Колонка | Тип |
|---|---|
| key | VARCHAR(100) PK |
| value | TEXT |
| description | VARCHAR(500) |
| updated_at | TIMESTAMPTZ |
| updated_by | VARCHAR(255) |

Ключи: `monitor.mail_to`, `monitor.enabled`, `revocation.mode`,
`auth.rate_limit.login`, `auth.rate_limit.window_min`, `smtp.*`,
`audit.retention_days`, `keys.last_backup_at`, `keys.download_count`.

### audit_events

| Колонка | Тип |
|---|---|
| event_time | TIMESTAMPTZ |
| event_type | VARCHAR(50) |
| result | VARCHAR(20): SUCCESS / FAIL / WARN |
| actor_email | VARCHAR(500) |
| actor_role | VARCHAR(30) |
| actor_ip | VARCHAR(64) |
| target_type | VARCHAR(50) |
| target_id | VARCHAR(50) |
| target_info | VARCHAR(500) |
| details | TEXT |
| user_agent | VARCHAR(500) |

Индексы: `event_time DESC`, `event_type`, `actor_email`, `result`.

### monitor_events

| Колонка | Тип |
|---|---|
| checked_at | TIMESTAMPTZ |
| status | VARCHAR(20): OK / FAIL |
| reason | VARCHAR(500) |
| alert_sent | BOOLEAN |

### flyway_schema_history — стандартная

Миграции V1–V14:

| V | Что делает |
|---|---|
| 1 | init: users, documents |
| 2 | grants: роли БД, права |
| 3 | ownership: владелец схемы |
| 4 | login_token_used_at |
| 5 | document_signatures |
| 6 | file_encryption: file_keys + AES-поля |
| 7 | app_settings |
| 8 | monitor_history |
| 9 | pii_user: email_enc/hash, phone_enc/hash |
| 10 | pii_documents: original_name_enc, signer_subject_enc |
| 11 | audit_events |
| 12 | audit_retention |
| 13 | smtp_settings |
| 14 | drop plaintext email/phone/original_name/signer_subject |

---

## Криптография УКЭП

### Проверка подписи

Класс `SignatureVerifier` — на BouncyCastle (не JCSP).

**Шаги:**

1. **Очистка base64:** убрать `-----BEGIN...-----`, пробелы, не-печатные.
2. **Парсинг CMS:** `CMSSignedData(content, signatureBytes)`. Content известен (файл или challenge).
3. **Поиск сертификата подписанта:** `cms.getCertificates().getMatches(signer.getSID())`.
4. **Криптопроверка:**
   - BouncyCastle `JcaSimpleSignerInfoVerifierBuilder` (primary)
   - JCSP (fallback для ГОСТ-2012)
5. **Chain building** (ручной, не PKIX):
   - Начинаем с leaf, ищем issuer по `subject == issuerX500Principal`
   - Проверяем подпись каждого звена (`cert.verify(issuer.getPublicKey())`)
   - До самоподписанного корня
6. **Проверка корня в truststore** (`/app/certs/*.cer`):
   - `subject` и `publicKey` должны совпасть
7. **Проверка срока каждого звена:** `cert.checkValidity()`
8. **Проверка отзыва по CRL** (`/app/crls/*.crl`):
   - Ищем CRL по `issuer`
   - Дедупликация по issuer — берём свежайший
   - Если сертификат в CRL → отказ

**Почему не JCSP `cades.verify()`:** JCSP игнорирует переданный truststore
и ищет корни в своём `/var/opt/cprocsp`, которого в контейнере нет.
Отсюда ошибка «Root certificate is untrusted» на любом сертификате клиента.

### Truststore

Источники в `SignatureVerifier.loadTrustedCerts()`:

1. `/app/certs/*.cer` — корневые УЦ (монтируется из `certs/`)
2. `$JAVA_HOME/lib/security/cacerts` — Java truststore

### Что видит сервер

- CN, СНИЛС (если есть)
- Serial (hex)
- X.500 DN
- Issuer DN
- ValidFrom / ValidTo

**Приватный ключ НЕ покидает токен.** Плагин CAdES подписывает локально,
отправляет только подпись.

### Валидация на фронте

`sign.js` после подписи получает от сервера `{valid: true, signersCount, signersInfo}`.
При `valid=false` — ошибка, подпись не сохраняется.

---

## Управление CRL

### Архитектура

`CrlDownloader` — сервис в приложении.

**При каждой проверке подписи:**

1. Извлекает все сертификаты из CMS (`cms.getCertificates()`)
2. Для каждого — читает CDP URL из расширения `2.5.29.31`
3. Для каждого URL:
   - filename = `auto-<sha256(url)[:16]>.crl`
   - если файл существует и младше 12 часов — пропускаем
   - иначе скачиваем с `User-Agent: Afina-CRL-Downloader/1.0`
4. `SignatureVerifier` читает **все** `*.crl` из `/app/crls/`
5. Дедупликация по issuer — оставляется самый свежий (по `thisUpdate`)

### Cron

- `afina-crl-cleanup` (вс 4:00) — `cleanup-crls.sh` удаляет `auto-*.crl`
  старше 90 дней
- Ручные CRL (не `auto-*`) не удаляются никогда

### Что делать вручную

Если УЦ не даёт стабильный CDP URL или он недоступен:

1. Скачать `.crl` вручную с сайта УЦ
2. Положить в `/opt/afina/crls/`
3. `chmod 644`, `chown 999:999`
4. `afina restart`

### Ошибка «Нет CRL для …»

В `checkNotRevoked()` — если CRL для issuer не найден, **warning** и
проверка отзыва для этого звена пропускается. Это **soft-fail**:
подпись принимается.

Текущий режим: `revocation.mode = soft` в `app_settings`.

| Режим | Поведение |
|---|---|
| `strict` | Отказ при отсутствии CRL (не реализовано) |
| `soft` | Warning, подпись принимается (текущий) |
| `off` | Проверка отзыва полностью отключена |

---

## Rate limiting

`RateLimitService` — in-memory `ConcurrentHashMap<key, Bucket>`,
fixed window. `Bucket = (AtomicInteger count, Instant resetAt)`.

`RateLimitFilter` (`@Order(HIGHEST_PRECEDENCE)`) — работает **до**
Spring Security, чтобы CSRF не блокировал запросы раньше rate limit.

| Endpoint | Лимит | Ключ |
|---|---|---|
| `POST /login` | 5 / 15 мин | `login:<ip>:<email>` |
| `GET /admin/challenge` | 10 / 15 мин | `challenge:<ip>` |
| `POST /admin/cert-login` | 5 / 15 мин | `certlogin:<ip>` |
| `GET /login/confirm` | 20 / 15 мин | `confirm:<ip>` |

При превышении: `429 Too Many Requests` + `Retry-After: <секунды>`.

`X-Forwarded-For` от Caddy — берётся первый IP.

**Клиент очистки:** `@Scheduled(fixedDelay = 300_000)` в `RateLimitService`.

Для горизонтального масштабирования нужно Redis-based решение.

---

## Аудит

`AuditService` пишет события в `audit_events` в `@Transactional(REQUIRES_NEW)`,
чтобы падение audit не откатывало основную бизнес-операцию.

**Типы событий:**

- `LOGIN_SUCCESS`, `LOGIN_FAIL`
- `ADMIN_LOGIN_SUCCESS`, `ADMIN_LOGIN_FAIL`
- `USER_CREATE`, `USER_UPDATE`, `USER_DELETE`
- `DOC_UPLOAD`, `DOC_DELETE`
- `SIGN_SUCCESS`, `SIGN_FAIL`
- `SIGNATURE_DELETE_ATTEMPT` (попытки обхода защиты)
- `EMAIL_SENT`, `EMAIL_FAIL`
- `BACKUP_CREATE`, `BACKUP_RESTORE`
- `APP_RESTART`, `SETTINGS_UPDATE`, `KEYS_DOWNLOAD`
- `RATE_LIMIT`

**Поля:**
- `actor_email` — email клиента или CN админа
- `actor_ip` — реальный IP (с XFF)
- `target_type` / `target_id` / `target_info` — что затронуто
- `details` — свободный текст (ошибки, обстоятельства)
- `user_agent` — браузер

**Retention:** `audit.retention_days` (по умолчанию 365).
`AuditCleanupService.dailyCleanup()` в 04:00 + кнопка «Очистить сейчас»
в UI.

---

## Мониторинг

Два независимых механизма:

### 1. Внутренний (`MonitoringService`)

- `@Scheduled(fixedDelay = 300_000, initialDelay = 60_000)`
- Проверяет `http://localhost:8080/actuator/health`
- Пишет в `monitor_events`
- Счётчик подряд идущих сбоев в `app_settings`
- При 3 подряд → `EmailService.sendAlert()`
- **Не работает, если app полностью упал** (живёт внутри app)

### 2. Внешний (`monitor.sh`, cron каждые 5 минут)

- Проверяет: локальный health через `docker exec`, внешний HTTPS,
  контейнеры через `docker compose ps`
- State в `logs/monitor.state` (consecutive fails)
- При 3 сбоях → письмо через Python SMTP (читает `monitor.mail_to`
  из БД)
- **Работает даже если app упал**

Оба шлют email на адрес из `monitor.mail_to` в `app_settings`.
### Аутентификация

| Метод | Путь | Auth | Назначение |
|---|---|---|---|
| GET | / | user | redirect /login или /dashboard |
| GET | /login | – | форма входа |
| POST | /login | – | запросить magic-link |
| GET | /login/confirm | – | вход по токену |
| POST | /logout | user | выход клиента |
| GET | /admin/login | IP | форма входа админа |
| GET | /admin/challenge | IP | одноразовый челлендж |
| POST | /admin/cert-login | IP | вход по УКЭП |
| POST | /admin/logout | admin | выход админа |

### Документы клиента

| Метод | Путь | Auth | Назначение |
|---|---|---|---|
| GET | /dashboard | user | список документов |
| POST | /documents/upload | user | загрузить (multipart) |
| GET | /documents/{id}/view | user | просмотр (inline) |
| GET | /documents/{id}/download | user | скачать оригинал |
| GET | /documents/{id}/download-signed | user | ZIP: файл + подпись |
| GET | /documents/{id}/signatures/{sigId}/download | user | скачать .sig |
| POST | /documents/{id}/signatures/{sigId}/delete | user | запрещено (400) |
| POST | /documents/{id}/delete | user | удалить (только неподписанный) |

### Подпись

| Метод | Путь | Auth | Назначение |
|---|---|---|---|
| POST | /api/sign/accept | user | подписать свой документ |
| POST | /api/sign/admin/accept | admin | подписать документ клиента |

Request:
```json
{
  "documentId": 42,
  "signatureBase64": "MIIF..."
}


---
