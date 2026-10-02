# Афина — Техническая документация

Документ для ИТ-специалистов: архитектура, модель безопасности,
схема данных, API, эксплуатация.

---

## Содержание

1. [Обзор архитектуры](#обзор-архитектуры)
2. [Компоненты](#компоненты)
3. [Потоки данных](#потоки-данных)
4. [Модель аутентификации](#модель-аутентификации)
5. [Шифрование файлов](#шифрование-файлов)
6. [Модель ролей БД](#модель-ролей-бд)
7. [Схема БД](#схема-бд)
8. [Криптография УКЭП](#криптография-укэп)
9. [API](#api)
10. [Эксплуатация](#эксплуатация)

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

**Ключевое:**

- Приватный ключ УКЭП **никогда не покидает токен** клиента
- Сервер видит только публичный сертификат и подпись
- Файлы на диске зашифрованы AES-256-GCM
- Caddy сам получает и обновляет сертификаты Let's Encrypt

---

## Компоненты

### 1. Приложение (Spring Boot)

Контейнер `afina-app`, порт 8080 (не публикуется наружу).
Читает `.env.prod`, монтирует `storage/`, `logs/`, `backups/`, `secrets/`.

Ключевые модули:

| Модуль | Назначение |
|---|---|
| `SecurityConfig` | Две независимые цепочки: admin (IP whitelist) и user |
| `AdminCertAuthController` | Вход админов по УКЭП (challenge + подпись) |
| `AuthController` | Вход клиентов по magic-link |
| `SignatureVerifier` | Проверка CAdES-BES через JCSP + BouncyCastle |
| `DocumentService` | Загрузка/скачивание/шифрование файлов |
| `FileEncryptor` | AES-256-GCM шифрование |
| `BackupService` | pg_dump + psql restore |

### 2. PostgreSQL 16

Контейнер `afina-postgres`, порт 5432 (не публикуется).
Образ `afina-postgres:16-pgaudit` — PostgreSQL + pgAudit.

Расширения:

- `pg_stat_statements` — мониторинг запросов
- `pgaudit` — аудит DDL/DML
- `pgcrypto` — криптография
- `uuid-ossp` — UUID-генерация

### 3. Caddy 2

Контейнер `afina-caddy`, порты 80/443.
Reverse-proxy с автоматическим TLS от Let's Encrypt.

`Caddyfile`:

    afina.example.ru {
        reverse_proxy app:8080
    }

### 4. Хранилище

`/opt/afina/storage/documents/` — файлы зашифрованы, имя `<uuid>_<orig>.enc`.

`/opt/afina/secrets/file.key` — ключ AES-256, права 600 uid=999.

`/opt/afina/backups/` — дампы БД (`afina_*.sql.gz`) + архивы файлов
(`storage_*.tar.gz`).

`/opt/afina/logs/` — логи приложения.

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
              - return (IV || ciphertext) + IV_base64
         c. Files.write(storage/<uuid>_<orig>.enc, IV||ct)
         d. sha256 = SHA-256(plaintext)
         e. INSERT INTO documents (original_name, stored_name, size,
                                   file_sha256, encryption_iv, key_version,
                                   encrypted=true, ...)
    4. Redirect /dashboard

### Скачивание документа

    1. Клиент → GET /documents/{id}/download
    2. DocumentController.download()
    3. DocumentService.getOwned(id, user) — проверка владельца
    4. DocumentService.getBytes(doc):
         a. Files.readAllBytes(storage/<stored_name>)
         b. FileEncryptor.decrypt(raw):
              - IV = raw[0..11]
              - AES-256-GCM.decrypt(key, IV, raw[12..])
              - return plaintext
    5. ResponseEntity<Resource> с plaintext

### Подписание документа (клиент)

    1. Клиент нажимает «Подписать»
    2. JS: fetch /documents/{id}/view → arrayBuffer → base64
    3. JS: cadesplugin.signBase64(thumbprint, contentBase64)
         — плагин подписывает ЛОКАЛЬНО, ключ не уходит с токена
    4. JS: POST /api/sign/accept {documentId, signatureBase64}
    5. SignApiController:
         - getOwned(id, user)
         - documentService.getBytes(doc) → plaintext
         - signatureVerifier.verifyDetached(plaintext, signatureB64)
             • JCSP/RevCheck: проверка цепочки, CRL/OCSP
             • возвращает {signerSubject, signerSerial, signersCount}
         - documentService.addSignature(): INSERT в document_signatures
    6. 200 OK с информацией о подписантах

### Вход администратора (УКЭП)

    1. GET /admin/login → HTML-страница
    2. JS: GET /admin/challenge → {challenge: "<uuid>-<timestamp>"}
       (сервер сохраняет в in-memory map, TTL 5 минут)
    3. JS: signBase64(thumbprint, btoa(challenge))
    4. JS: POST /admin/cert-login {cn, snils, challenge, signatureBase64}
    5. AdminCertAuthController:
         - challenges.remove(challenge) → одноразовость
         - adminsFile.find(cn, snils) → 403 если нет
         - signatureVerifier.verifyDetached(challenge, signature)
         - extractCnFromLastSignature() == admins.env CN → 403 если нет
         - userService.upsertAdminByCert(cn, snils)
         - SecurityContextHolder → ADMIN_SECURITY_CONTEXT
         - adminContextRepository.saveContext(ctx, req, resp)
    6. 200 OK {success: true, redirect: "/admin"}

### Вход клиента (magic-link)

    1. POST /login {email}
    2. UserService.generateLoginLink(email, baseUrl):
         - если юзер не найден или !enabled → return null (не палим)
         - token = UUID.randomUUID().replace("-", "")
         - UPDATE users SET login_token=?, login_token_expires=now+10h
         - return baseUrl + "/login/confirm?token=" + token
    3. EmailService.sendLoginLink() — SMTP или лог
    4. Клиент переходит → GET /login/confirm?token=...
    5. UserService.consumeLoginToken(token):
         - findByLoginToken
         - проверка expires
         - токен многоразовый до истечения TTL (10 часов), защита от
           Gmail-prefetch, который «съедает» первый клик
         - UPDATE users SET last_login_at=now, login_token_used_at
         - return User
    6. SecurityContextHolder → USER_SECURITY_CONTEXT
    7. Redirect /dashboard

---

## Модель аутентификации

### Два независимых контекста

В одном HTTP-сеансе могут одновременно жить две разные аутентификации:

| Контекст | Ключ в HttpSession | Кто |
|---|---|---|
| `USER_SECURITY_CONTEXT` | `USER_SECURITY_CONTEXT` | Клиент (magic-link) |
| `ADMIN_SECURITY_CONTEXT` | `ADMIN_SECURITY_CONTEXT` | Админ (УКЭП) |

Реализуется через два `HttpSessionSecurityContextRepository` с разными
`springSecurityContextKey`. Логаут одного не затрагивает другого.

### Цепочки фильтров Spring Security

**`@Order(1)` — adminChain:**

- `securityMatcher("/admin/**", "/api/sign/admin/**")`
- `adminIpFilter` — пропускает только IP из `app.admin-ip-whitelist`
- `.anyRequest().hasRole("ADMIN")`
- AuthenticationEntryPoint: AJAX → 401 JSON, иначе redirect `/admin/login`
- AccessDeniedHandler: 403

**`@Order(2)` — userChain:**

- Всё остальное
- Открытые пути: `/`, `/login`, `/login/confirm`, `/error`,
  `/actuator/health`, `/actuator/info`, `/ping`, `/css/**`, `/js/**`,
  `/favicon.ico`, `/h2-console/**`
- `.anyRequest().authenticated()`

CSRF отключён для обеих цепочек (cert-auth + IP whitelist для админов,
stateless-токен в magic-link для клиентов).

### Логин админа по УКЭП

Реализация — `AdminCertAuthController`.

**Защита от replay:**

- Одноразовый challenge хранится в `ConcurrentHashMap` с TTL 5 минут
- `challenges.remove()` при первом использовании — второй раз не пройдёт
- TTL-очистка при каждом новом запросе (`cleanOldChallenges`)
- Challenge включает timestamp: `<uuid>-<millis>`

**Проверка подписи:**

1. Base64-декодирование → `CAdESSignature`
2. `cades.verify(trustedCerts, crls)` — JCSP проверяет цепочку до
   корневого УЦ и отзыв по CRL
3. Дополнительно BouncyCastle `CMSSignedData` — fallback-проверка

**Что защищено:**

- Подделка CN/СНИЛС в POST — отсекается сравнением с `admins.env`
- Подмена IP — фильтр на уровне сервлета
- MITM при HTTPS — TLS 1.2/1.3 + HSTS от Caddy
- Replay challenge — одноразовый + TTL

**Что НЕ защищено (осознанные компромиссы):**

- Rate limiting на `/admin/challenge` — нет (можно добавить)
- Брутфорс `admins.env` — нет ограничения попыток

### Логин клиента по magic-link

Реализация — `AuthController`, `UserService`.

**Токен:**

- Генерация: `UUID.randomUUID().replace("-","")` — 32 hex-символа
- TTL: 10 часов (`LOGIN_TOKEN_TTL`)
- Хранение: `users.login_token`, `users.login_token_expires`
- Многоразовость в пределах TTL (антипаттерн для безопасности, но
  необходимо для обхода Gmail-prefetch и корпоративных почтовых
  сканеров, которые «кликают» все ссылки в письмах)

**Что защищено:**

- Энумерация email — не палим существование (всегда 200 OK)
- Юзер отключён (`enabled=false`) — токен не выпускается
- TTL 10 часов ограничивает окно злоупотребления
- `last_login_at` фиксируется для аудита

**Что НЕ защищено:**

- Rate limiting на `/login` — нет (можно отправить 1000 писем/час на
  один email)
- Повторное использование токена до истечения TTL (компромисс)

---

## Шифрование файлов

### Алгоритм

- **Шифр:** AES-256-GCM (`AES/GCM/NoPadding`, JCE SunJCE)
- **Ключ:** 32 байта (256 бит), base64 в `/opt/afina/secrets/file.key`
- **IV:** 12 байт, случайные для каждого файла (`SecureRandom`)
- **AuthTag:** 16 байт (128 бит), автоматически добавляется GCM

### Формат файла на диске

    [IV 12 байт][ciphertext][authTag 16 байт]

IV дублируется в БД (`documents.encryption_iv`, base64) для
идентификации параметров шифрования при будущей ротации.

### Что шифруется

- **Только контент файлов** в `storage/documents/*.enc`
- **НЕ шифруются:**
  - Метаданные в БД (`original_name`, `size`, `file_sha256`) —
    необходимы для поиска и отображения
  - Подписи (в `documents.signature_base64`, `document_signatures`)
  - Логи, конфиги, сертификаты

### Жизненный цикл ключа

- **Создание:** один раз на сервере `openssl rand -base64 32`
- **Ротация:** требует утилиты `RotateEncryptionKey` (не реализовано).
  Поле `documents.key_version` зарезервировано для будущих ключей.
- **Резервная копия:** обязательно вне сервера (1Password, сейф)
- **Компромисс:** при потере ключа — **все зашифрованные файлы
  безвозвратно утеряны**

### Что защищает и что нет

| Угроза | Защита |
|---|---|
| Кража HDD / snapshot диска | ✅ Криптомусор без ключа |
| Утечка через хостинг-провайдера | ✅ Только шифротекст |
| Случайный `scp storage/*` | ✅ |
| Root-компрометация при работающем сервере | ⚠️ Ключ доступен из `secrets/file.key` |
| Утечка бэкапа БД | ⚠️ Метаданные без шифрования |

Для защиты от root-компрометации нужен KMS (Vault, AWS KMS) —
отдельная итерация.

---

## Модель ролей БД

Три роли с разделением привилегий:

| Роль | Назначение | Права |
|---|---|---|
| `afina_migrator` | Flyway, владелец схемы | CREATE, ALTER, DROP, DML |
| `afina_app` | Приложение | SELECT, INSERT, UPDATE, DELETE |
| `afina_auditor` | Аудит | SELECT |

### Разделение доступа

- Приложение ходит **под `afina_app`** (`spring.datasource.username`)
- Flyway ходит **под `afina_migrator`** (`spring.flyway.user`)
- Superuser (`postgres`) используется **только** для:
  - Restore из бэкапа (`BackupService.runPsqlCommandAsSuperuser`)
  - Init-скриптов в первый запуск

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

- **SQL-инъекция** в приложении не даст DROP TABLE — `afina_app` не имеет DDL
- **Утечка пароля приложения** не откроет запись — только CRUD по данным
- **Аудитор** видит всё, но не может ничего изменить
- **Компрометация `afina_migrator`** опаснее, но пароль используется только Flyway при старте

---

## Схема БД

### users

| Колонка | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| email | VARCHAR(255) UNIQUE | для клиентов; для админов синтетический `<cn>+<snils>@ukep.local` |
| full_name | VARCHAR(255) | ФИО |
| phone | VARCHAR(50) | |
| role | VARCHAR(30) | ROLE_USER / ROLE_ADMIN |
| enabled | BOOLEAN | false = заблокирован |
| login_token | VARCHAR(128) | magic-link токен |
| login_token_expires | TIMESTAMPTZ | TTL 10 часов |
| login_token_used_at | TIMESTAMPTZ | первое использование |
| last_login_at | TIMESTAMPTZ | |
| created_at | TIMESTAMPTZ | |

Индексы: `email`, `login_token`, `role`.

### documents

| Колонка | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| original_name | VARCHAR(500) | имя от клиента |
| stored_name | VARCHAR(500) | `<uuid>_<sanitized>.enc` |
| content_type | VARCHAR(200) | MIME |
| size | BIGINT | размер ОРИГИНАЛА |
| file_sha256 | VARCHAR(64) | sha256 ОРИГИНАЛА |
| signature_base64 | TEXT | legacy, дублируется в document_signatures |
| signed | BOOLEAN | есть хотя бы одна подпись |
| signed_at | TIMESTAMPTZ | |
| signer_subject | VARCHAR(500) | CN первого подписанта |
| signer_serial | VARCHAR(100) | serial первого подписанта |
| uploaded_at | TIMESTAMPTZ | |
| owner_id | BIGINT FK → users(id) ON DELETE CASCADE | |
| encryption_iv | VARCHAR(32) | base64 IV (V6) |
| key_version | VARCHAR(20) FK → file_keys(version) | (V6) |
| encrypted | BOOLEAN | (V6) |

Индексы: `owner_id`, `signed`, `uploaded_at DESC`.

### document_signatures (V5)

| Колонка | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| document_id | BIGINT FK → documents(id) ON DELETE CASCADE | |
| signature_base64 | TEXT | CAdES-BES detached |
| signed_at | TIMESTAMPTZ | |
| signer_subject | VARCHAR(500) | CN |
| signer_serial | VARCHAR(100) | serial (hex) |
| signer_user_id | BIGINT FK → users(id) ON DELETE SET NULL | |

Индексы: `document_id`, `signed_at DESC`.

**Особенность:** одна запись = одна подпись. Документ может иметь
неограниченное число подписей (несколько сторон, повторные подписи).

### file_keys (V6)

| Колонка | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| version | VARCHAR(20) UNIQUE | `v1`, `v2`, ... |
| algorithm | VARCHAR(50) | `AES-256-GCM` |
| created_at | TIMESTAMPTZ | |
| active | BOOLEAN | используется для новых файлов |
| comment | VARCHAR(500) | |

Одна активная версия в любой момент времени.

### flyway_schema_history

Стандартная таблица Flyway. Миграции:

| V | Файл | Что делает |
|---|---|---|
| 1 | V1__init.sql | users, documents + индексы + комментарии |
| 2 | V2__grants.sql | роли БД, права, default privileges |
| 3 | V3__ownership.sql | владелец схемы public |
| 4 | V4__login_token_used_at.sql | колонка для анти-prefetch |
| 5 | V5__document_signatures.sql | таблица множественных подписей |
| 6 | V6__file_encryption.sql | AES-GCM: file_keys + поля documents |

---

## Криптография УКЭП

### Проверка подписи на сервере

Класс `SignatureVerifier` (основной путь — BouncyCastle) и
`SignatureService` (JCSP/CAdES, legacy). Оба используют одни и те же
принципы.

**Шаги:**

1. **Очистка base64:**
   - Убираются `-----BEGIN ...-----` / `-----END ...-----`
   - Убираются пробелы и непечатные символы

2. **Парсинг CMS/CAdES:**
   - `CMSSignedData(content, signatureBytes)` — контент известен (это
     файл или challenge), подпись отдельно (detached)
   - `cms.getSignerInfos().getSigners()` — список подписантов

3. **Поиск сертификата подписанта:**
   - `cms.getCertificates().getMatches(signer.getSID())` — по SID
     из подписи
   - Если сертификата в подписи нет — ошибка

4. **Криптографическая проверка:**
   - `signer.verify(JcaSimpleSignerInfoVerifierBuilder.build(cert))`
   - BouncyCastle проверяет подпись по публичному ключу
   - ГОСТ-2012 поддерживается BC + CryptoPro JCSP

5. **Извлечение метаданных:**
   - `cert.getSubjectX500Principal()` — X.500 DN
   - Парсинг CN через regex `CN=([^,]+)` или `LdapName`
   - `cert.getSerialNumber().toString(16)` — serial

**Что НЕ проверяет `SignatureVerifier` (упрощённый путь):**

- Цепочку до корневого УЦ
- Отзыв по CRL/OCSP
- Срок действия сертификата

Эти проверки делает `SignatureService` через CryptoPro JCSP:
`cades.verify(trustedCerts, crls)`.

### Truststore

Источники доверенных сертификатов (`SignatureService.loadTrustedCerts`):

1. `/app/certs/*.cer` и `*.crt` — корневые УЦ и промежуточные
   (монтируется из `certs/` репозитория)
2. `$JAVA_HOME/lib/security/cacerts` — стандартный Java truststore
   (`changeit`)

### CRL

`kontur-q-2025.crl` монтируется в `/app/kontur-q-2025.crl` (read-only).

Проверка отзыва — через системные свойства JVM
(`CryptoProInitializer`):

    com.sun.security.enableCRLDP=true
    ocsp.enable=true
    ru.CryptoPro.reprov.enableCRLDP=true
    ru.CryptoPro.reprov.enableAIAcaIssuers=true

Без этих свойств CAdES падает с ошибкой
`Could not determine revocation status`.

### Что видит сервер

При верификации сервер получает:

- CN (ФИО), SNILS (если в сертификате)
- Serial (hex)
- X.500 DN
- Issuer DN
- ValidFrom / ValidTo

**Приватный ключ НЕ покидает токен.** Плагин CryptoPro CAdES
подписывает **локально** на клиенте, отправляет только подпись.
Используется `CADESCOM_CADES_BES` + `CAPICOM_CERTIFICATE_INCLUDE_END_ENTITY_ONLY`.

### Схема подписи

- Формат: **CAdES-BES detached**
- Контент — оригинальный файл (не шифротекст)
- Подпись хранится в БД (base64), не в файловой системе
- Один документ — N подписей

### Валидация на фронте

`sign.js` после успешной подписи получает от сервера:

    {valid: true, signersCount: N, signersInfo: "..."}

Если `valid=false` — показывается ошибка, подпись не сохраняется.

---

## API

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
| GET | /documents/{id}/signature/download | user | скачать .sig (legacy, первая подпись) |
| GET | /documents/{id}/signatures/{sigId}/download | user | скачать конкретную подпись |
| POST | /documents/{id}/signatures/{sigId}/delete | user | удалить подпись |
| POST | /documents/{id}/delete | user | удалить документ |

### Подпись

| Метод | Путь | Auth | Назначение |
|---|---|---|---|
| POST | /api/sign/accept | user | подписать свой документ |
| POST | /api/sign/admin/accept | admin | подписать документ клиента |

Request body (JSON):

    {
      "documentId": 42,
      "signatureBase64": "MIIF..."
    }

Response (200):

    {
      "valid": true,
      "signersCount": 1,
      "signersInfo": "CN=..., serial=..."
    }

### Админ

| Метод | Путь | Auth | Назначение |
|---|---|---|---|
| GET | /admin | admin | дашборд |
| GET | /admin/users | admin | список клиентов |
| POST | /admin/users | admin | создать |
| GET | /admin/users/{id} | admin | карточка клиента |
| GET | /admin/users/{id}/edit | admin | форма редактирования |
| POST | /admin/users/{id} | admin | обновить |
| POST | /admin/users/{id}/delete | admin | удалить |
| POST | /admin/users/{id}/toggle | admin | вкл/выкл |
| POST | /admin/users/{id}/send-login-link | admin | отправить magic-link |
| GET | /admin/users/{id}/signatures.zip | admin | ZIP всех подписей |
| POST | /admin/users/{userId}/documents/upload | admin | загрузить за клиента |
| GET | /admin/documents/{id}/view | admin | просмотр |
| GET | /admin/documents/{id}/download | admin | скачать |
| POST | /admin/documents/{id}/delete | admin | удалить |
| GET | /admin/documents/{id}/signature/download | admin | скачать подпись |
| GET | /admin/documents/{id}/signatures/{sigId}/download | admin | |
| POST | /admin/documents/{id}/signatures/{sigId}/delete | admin | |

### Система

| Метод | Путь | Auth | Назначение |
|---|---|---|---|
| GET | /admin/system | admin | страница «Система» |
| POST | /admin/system/backups/create | admin | создать бэкап |
| GET | /admin/system/backups/{name}/download | admin | скачать бэкап |
| POST | /admin/system/backups/{name}/delete | admin | удалить бэкап |
| POST | /admin/system/backups/{name}/restore | admin | восстановить БД |
| GET | /admin/system/logs?lines=N | admin | tail лога |
| POST | /admin/system/restart | admin | перезапуск приложения |
| GET | /actuator/health | – | healthcheck |

---

## Эксплуатация

### Обновление без downtime

Текущая схема — простой перезапуск (30–60 сек простоя).
Для zero-downtime нужен blue-green (см. отдельную итерацию).

Процедура:

    cd /opt/afina
    git pull
    docker compose -f docker-compose-prod.yml --env-file .env.prod \
      build --no-cache app
    docker compose -f docker-compose-prod.yml --env-file .env.prod \
      up -d app

Flyway применит новые миграции.

### Мониторинг

Доступные метрики:

- `/actuator/health` — UP/DOWN + статус БД
- `/actuator/info`
- pgAudit-логи (DML/DDL)
- `pg_stat_statements` — топ запросов
- UI `/admin/system` — CPU, RAM, диск, размер БД, миграции, логи

**Нет:** Prometheus-метрик, Grafana, алертов. Отдельная итерация.

### Бэкап и восстановление

Автоматически:

- Cron `/etc/cron.d/afina-backup` — ежедневно 3:00
- `backup-prod.sh` — дамп БД + архив `storage/`
- Ротация: 30 дней

Вручную:

    cd /opt/afina
    ./backup-prod.sh
    # или через UI /admin/system

**Ключ шифрования НЕ в бэкапе.** Хранить отдельно.

### Обновление схемы БД

Новая миграция:

1. Создать `src/main/resources/db/migration/V{N+1}__description.sql`
2. Git push
3. На сервере: `git pull && docker compose ... up -d app`
4. Flyway применит при старте

**Нельзя** менять уже применённые миграции — Flyway ругнётся при валидации.

### Ротация ключа шифрования

Не реализована. Требуется утилита `RotateEncryptionKey`:

1. Читает старый ключ из `OLD_PATH`
2. Читает новый ключ из `NEW_PATH`
3. Для каждого `Document`: decrypt(old) → encrypt(new)
4. Обновляет `encryption_iv`, `key_version` на новый
5. Перезаписывает файл

Пока ключ один (`v1`). Поле `key_version` в БД подготовлено.

### Диагностика

UI `/admin/system` → раздел «Диагностика»:

- Доступность БД
- Роли (3 ожидаются)
- Документы без владельца
- Подписи без документа
- Размер БД
- Все миграции успешны
- Активные соединения

CLI:

    docker exec afina-postgres psql -U postgres -d afina_db -c "\dt"
    docker exec afina-postgres psql -U postgres -d afina_db -c "\dx"
    docker exec afina-postgres psql -U postgres -d afina_db -c \
      "SELECT * FROM pg_stat_activity WHERE datname='afina_db';"

### Обновление security-патчей

Unattended-upgrades установлен, автоматом ставит только security-обновления.
Проверить:

    unattended-upgrade --dry-run -d

Перезагрузка после ядерных патчей:

    # убедиться, что всё в порядке:
    docker compose -f /opt/afina/docker-compose-prod.yml \
      --env-file /opt/afina/.env.prod ps
    reboot

После ребута все контейнеры поднимутся автоматически (`restart: unless-stopped`).

### Известные ограничения

- Нет rate-limiting на `/login` и `/admin/challenge`
- Нет MFA (только сертификат или email)
- Нет WAF перед Caddy
- Нет мониторинга (Prometheus/Grafana)
- Нет репликации БД
- Нет zero-downtime deploy
- Ключ шифрования на том же сервере (не KMS)
- Бэкапы не шифруются (но дампы БД содержат только метаданные)
- Бэкапы не выгружаются на внешнее хранилище

### Дальнейшие итерации

1. **WAF** — Cloudflare или ModSecurity+OWASP CRS
2. **Rate limiting** — Bucket4j или Caddy rate_limit
3. **KMS** — HashiCorp Vault для ключа шифрования
4. **Мониторинг** — Prometheus + Grafana + алерты
5. **Zero-downtime deploy** — blue-green через Caddy upstream
6. **Ротация ключа** — утилита `RotateEncryptionKey`
7. **Внешние бэкапы** — S3 / rclone раз в сутки
8. **Docker healthcheck** — сейчас есть только у app и postgres
9. **2FA для админов** — ТОП по желанию
10. **Репликация БД** — streaming replication для HA

---

## Ссылки

- Spring Boot: https://docs.spring.io/spring-boot/docs/3.2.5/reference/html/
- PostgreSQL 16: https://www.postgresql.org/docs/16/
- Flyway: https://documentation.red-gate.com/flyway
- pgAudit: https://github.com/pgaudit/pgaudit
- Caddy 2: https://caddyserver.com/docs/
- КриптоПро CSP: https://www.cryptopro.ru/products/csp
- КриптоПро CAdES: https://www.cryptopro.ru/products/cades
