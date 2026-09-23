# ============ Сборка ============
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml ./
COPY libs ./libs

# Устанавливаем JAR КриптоПро в локальный ~/.m2
RUN cd libs && \
    VERSION=5.0.49800 && G=ru.cryptopro.jcsp && \
    for j in JCSP:jcsp JCP:jcp JCryptoP:jcryptop JCPRevCheck:jcp-revcheck \
             JCPRevTools:jcp-revtools CAdES:cades AdES-core:ades-core \
             ASN1P:asn1p asn1rt:asn1rt cmsutil:cmsutil JCPxml:jcp-xml \
             XAdES:xades XMLDSigRI:xmldsigri Rutoken:rutoken; do \
        f="${j%%:*}"; a="${j##*:}"; \
        mvn -B -q install:install-file -Dfile="$f.jar" -DgroupId=$G -DartifactId=$a -Dversion=$VERSION -Dpackaging=jar; \
    done && \
    mvn -B -q install:install-file -Dfile=bcprov-jdk18on-1.78.1.jar -DgroupId=org.bouncycastle -DartifactId=bcprov-jdk18on -Dversion=1.78.1 -Dpackaging=jar && \
    mvn -B -q install:install-file -Dfile=bcpkix-jdk18on-1.78.1.jar -DgroupId=org.bouncycastle -DartifactId=bcpkix-jdk18on -Dversion=1.78.1 -Dpackaging=jar && \
    mvn -B -q install:install-file -Dfile=bcutil-jdk18on-1.78.1.jar -DgroupId=org.bouncycastle -DartifactId=bcutil-jdk18on -Dversion=1.78.1 -Dpackaging=jar

COPY src ./src
RUN mvn -B clean package -DskipTests

# ============ Запуск ============
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN useradd -ms /bin/bash appuser && \
    mkdir -p /app/storage/documents && \
    chown -R appuser:appuser /app

COPY --from=build /app/target/ukep-sign-service.jar app.jar

USER appuser
EXPOSE 8080

ENTRYPOINT ["java","-jar","/app/app.jar"]
