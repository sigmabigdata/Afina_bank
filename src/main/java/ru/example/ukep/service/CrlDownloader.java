package ru.example.ukep.service;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * Загружает CRL по URL из CDP-расширения (CRL Distribution Points)
 * сертификатов подписантов.
 *
 * Файлы кладутся в crlDir с именем <sha256(url)[:16]>.crl.
 * Идемпотентно: если файл уже есть и младше maxAgeHours — не качаем.
 * Ошибки загрузки не валят проверку, только логируются.
 */
@Service
public class CrlDownloader {

    private static final Logger log = LoggerFactory.getLogger(CrlDownloader.class);

    private static final String CDP_OID = "2.5.29.31";

    private final Path crlDir;
    private final long maxAgeHours;

    public CrlDownloader(
            @Value("${app.crl-path:/app/crls}") String crlPath,
            @Value("${app.crl-max-age-hours:12}") long maxAgeHours) throws Exception {
        this.crlDir = Paths.get(crlPath).toAbsolutePath().normalize();
        Files.createDirectories(crlDir);
        this.maxAgeHours = maxAgeHours;
        log.info("CrlDownloader: директория {}, TTL {} ч", crlDir, maxAgeHours);
    }

    /**
     * Скачивает CRL для всех переданных сертификатов (идемпотентно).
     * Возвращает количество успешно загруженных/обновлённых файлов.
     */
    public int ensureCrlsFor(Set<X509Certificate> certs) {
        Set<String> urls = new HashSet<>();
        for (X509Certificate cert : certs) {
            urls.addAll(extractCdpUrls(cert));
        }
        int count = 0;
        for (String url : urls) {
            try {
                if (downloadIfStale(url)) count++;
            } catch (Exception e) {
                log.warn("CRL download failed для {}: {}", url, e.getMessage());
            }
        }
        return count;
    }

    /** Извлекает все CDP URL (http/https) из расширения 2.5.29.31. */
    private Set<String> extractCdpUrls(X509Certificate cert) {
        Set<String> urls = new HashSet<>();
        byte[] ext;
        try {
            ext = cert.getExtensionValue(CDP_OID);
        } catch (Exception e) {
            return urls;
        }
        if (ext == null) return urls;

        try (ASN1InputStream aIn = new ASN1InputStream(ext)) {
            ASN1OctetString octet = (ASN1OctetString) aIn.readObject();
            try (ASN1InputStream innerIn = new ASN1InputStream(octet.getOctets())) {
                ASN1Primitive p = innerIn.readObject();
                CRLDistPoint cdp = CRLDistPoint.getInstance(p);
                for (DistributionPoint dp : cdp.getDistributionPoints()) {
                    DistributionPointName name = dp.getDistributionPoint();
                    if (name == null || name.getType() != DistributionPointName.FULL_NAME) continue;
                    GeneralNames gns = GeneralNames.getInstance(name.getName());
                    for (GeneralName gn : gns.getNames()) {
                        if (gn.getTagNo() != GeneralName.uniformResourceIdentifier) continue;
                        String url = DERIA5String.getInstance(gn.getName()).getString();
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            urls.add(url);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("CDP parse failed для {}: {}",
                    cert.getSubjectX500Principal(), e.getMessage());
        }
        return urls;
    }

    /** Скачивает URL, если файла нет или он старше maxAgeHours. */
    private boolean downloadIfStale(String url) throws Exception {
        String name = urlToFilename(url);
        Path target = crlDir.resolve(name);

        if (Files.isRegularFile(target)) {
            long ageMs = System.currentTimeMillis() - Files.getLastModifiedTime(target).toMillis();
            long ageHours = ageMs / 3_600_000L;
            if (ageHours < maxAgeHours) {
                return false;
            }
        }

        Path tmp = Files.createTempFile(crlDir, "dl-", ".crl.tmp");
        try {
            downloadToFile(url, tmp);
            if (Files.size(tmp) < 100) {
                throw new IllegalStateException("файл слишком маленький");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("CRL обновлён: {} ({} байт)", target.getFileName(), Files.size(target));
            return true;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void downloadToFile(String url, Path target) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Afina-CRL-Downloader/1.0");
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IllegalStateException("HTTP " + code);
        }
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
    }

    private String urlToFilename(String url) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(url.getBytes("UTF-8"));
        String hex = HexFormat.of().formatHex(h).substring(0, 16);
        return "auto-" + hex + ".crl";
    }
}
