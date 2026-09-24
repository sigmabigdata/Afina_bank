# ============================================================
# Афина · Сборка образа приложения (с Linux CryptoPro CSP)
# ============================================================
# Для x86_64 (VPS). На Apple Silicon не соберётся — там dev-режим.
# ============================================================

# ---------- Сборка ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

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
    mvn -B -q install:install-file -Dfile=bcprov-jdk18on-1.78.1.jar \
        -DgroupId=org.bouncycastle -DartifactId=bcprov-jdk18on -Dversion=1.78.1 -Dpackaging=jar && \
    mvn -B -q install:install-file -Dfile=bcpkix-jdk18on-1.78.1.jar \
        -DgroupId=org.bouncycastle -DartifactId=bcpkix-jdk18on -Dversion=1.78.1 -Dpackaging=jar && \
    mvn -B -q install:install-file -Dfile=bcutil-jdk18on-1.78.1.jar \
        -DgroupId=org.bouncycastle -DartifactId=bcutil-jdk18on -Dversion=1.78.1 -Dpackaging=jar

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
    && rm -rf /var/lib/apt/lists/*

# ---- Установка КриптоПро CSP для Linux ----
COPY cryptopro-dist/linux-amd64_deb.tgz /tmp/cryptopro/
RUN cd /tmp/cryptopro && \
    tar xzf linux-amd64_deb.tgz && \
    cd linux-amd64_deb && \
    dpkg -i lsb-cprocsp-base_*.deb && \
    dpkg -i cprocsp-compat-debian_*.deb || true && \
    apt-get install -y ./*.deb && \
    /opt/cprocsp/bin/amd64/cpverify -version && \
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

VOLUME ["/app/storage", "/var/log/afina"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

USER afina
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Dfile.encoding=UTF-8"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
