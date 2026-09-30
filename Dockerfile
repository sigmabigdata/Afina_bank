# ============================================================
# Афина · Сборка образа приложения (с Linux CryptoPro CSP)
# ============================================================
# Для x86_64 (VPS). На Apple Silicon не соберётся — там dev-режим.
# ============================================================

# ---------- Сборка ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

ENV MAVEN_OPTS="-Xmx512m -Xms128m"

COPY pom.xml ./
COPY libs ./libs

RUN cd libs && \
    VERSION=5.0.49800 && G=ru.cryptopro.jcsp && \
    for j in JCSP:jcsp JCP:jcp JCryptoP:jcryptop JCPRevCheck:jcp-revcheck \
             JCPRevTools:jcp-revtools CAdES:cades AdES-core:ades-core \
             ASN1P:asn1p asn1rt:asn1rt cmsutil:cmsutil JCPxml:jcp-xml \
             XAdES:xades XMLDSigRI:xmldsigri Rutoken:rutoken; do \
        f="${j%%:*}"; a="${j##*:}"; \
        mvn -B -q install:install-file -Dfile="$f.jar" \
            -DgroupId=$G -DartifactId=$a -Dversion=$VERSION -Dpackaging=jar; \
    done && \
    mvn -B -q install:install-file -Dfile=bcprov-jdk15on-1.70.jar \
        -DgroupId=org.bouncycastle -DartifactId=bcprov-jdk15on -Dversion=1.70 -Dpackaging=jar && \
    mvn -B -q install:install-file -Dfile=bcpkix-jdk15on-1.70.jar \
        -DgroupId=org.bouncycastle -DartifactId=bcpkix-jdk15on -Dversion=1.70 -Dpackaging=jar && \
    mvn -B -q install:install-file -Dfile=bcutil-jdk15on-1.70.jar \
        -DgroupId=org.bouncycastle -DartifactId=bcutil-jdk15on -Dversion=1.70 -Dpackaging=jar

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- Запуск ----------
# ВАЖНО: jammy (Ubuntu 22.04 LTS) — совместим с пакетами CryptoPro
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Системные библиотеки + утилиты
RUN apt-get update -o Acquire::Retries=5 && \
    apt-get install -y --no-install-recommends \
        pcscd libpcsclite1 curl ca-certificates lsb-base \
        postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# ---- Установка КриптоПро CSP для Linux ----

# ---- Установка КриптоПро CSP для Linux ----
# ---- Установка КриптоПро CSP для Linux ----
# Устанавливаем ТОЛЬКО серверные пакеты. GUI (cptools-gtk, rdr-gui-gtk) и
# драйверы токенов (ifd-rutokens, rdr-*) не нужны для проверки подписи.
# ВАЖНО: lsb-cprocsp-rdr-64 нужен всем остальным пакетам — ставим его явно.
# ---- Установка КриптоПро CSP для Linux ----
# Устанавливаем ТОЛЬКО серверные пакеты.
# lsb-cprocsp-rdr-64 нужен всем остальным — ставим явно.
COPY cryptopro-dist/linux-amd64_deb.tgz /tmp/cryptopro/
RUN cd /tmp/cryptopro && \
    tar xzf linux-amd64_deb.tgz && \
    cd linux-amd64_deb && \
    apt-get install -y --no-install-recommends \
        ./lsb-cprocsp-base_*.deb \
        ./cprocsp-compat-debian_*.deb \
        ./lsb-cprocsp-rdr-64_*.deb \
        ./lsb-cprocsp-kc1-64_*.deb \
        ./lsb-cprocsp-capilite-64_*.deb \
        ./cprocsp-curl-64_*.deb \
        ./cprocsp-pki-cades-64_*.deb && \
    ls -la /opt/cprocsp/bin/amd64/ && \
    rm -rf /tmp/cryptopro

ENV PATH="${PATH}:/opt/cprocsp/bin/amd64:/opt/cprocsp/sbin/amd64"
ENV LD_LIBRARY_PATH="/opt/cprocsp/lib/amd64:${LD_LIBRARY_PATH}"

# Непривилегированный пользователь
RUN groupadd -r afina && useradd -r -g afina afina && \
    mkdir -p /app/storage/documents /var/log/afina \
             /var/opt/cprocsp/tmp /var/opt/cprocsp/keys/afina && \
    chown -R afina:afina /app /var/log/afina /var/opt/cprocsp && \
    chmod -R 775 /var/opt/cprocsp && chmod 777 /var/opt/cprocsp/tmp

COPY --from=build /app/target/ukep-sign-service.jar app.jar

# Сертификаты УЦ — внутрь образа
COPY certs/ /app/certs/

# Отключаем шум java.util.prefs (CryptoPro использует prefs для license)
ENV JAVA_TOOL_OPTIONS="-Djava.util.prefs.userRoot=/tmp/.java -Djava.util.prefs.systemRoot=/tmp/.java-system"


VOLUME ["/app/storage", "/var/log/afina"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

USER afina
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Dfile.encoding=UTF-8"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
