package ru.example.ukep.service;

import org.springframework.stereotype.Service;
import ru.CryptoPro.CAdES.CAdESSignature;
import ru.CryptoPro.CAdES.CAdESSigner;
import ru.CryptoPro.CAdES.CAdESType;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public class SignatureService {

    /** Кэш CN последней проверенной подписи (для синхронной логики verify→extract). */
    private final ThreadLocal<String> lastCn = new ThreadLocal<>();

    public Map<String, Object> verifyDetached(Path contentPath,
                                              String signatureBase64,
                                              Path crlPath) throws Exception {
        byte[] data = Files.readAllBytes(contentPath);
        return verifyDetached(data, signatureBase64, crlPath);
    }

    public Map<String, Object> verifyDetached(byte[] data,
                                              String signatureBase64,
                                              Path crlPath) throws Exception {
        return verifyInternal(data, signatureBase64, crlPath);
    }

    public Map<String, Object> verifyDetached(byte[] data,
                                              String signatureBase64,
                                              String crlPath) throws Exception {
        return verifyInternal(data, signatureBase64, Path.of(crlPath));
    }

    private Map<String, Object> verifyInternal(byte[] data,
                                               String signatureBase64,
                                               Path crlPath) throws Exception {
        String cleaned = signatureBase64
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s+", "")
                .replaceAll("[^A-Za-z0-9+/=]", "");
        byte[] signatureBytes = Base64.getDecoder().decode(cleaned);

        CAdESSignature cades = new CAdESSignature(signatureBytes, data, CAdESType.CAdES_BES);
        Set<X509CRL> crls = loadAllCrls(crlPath);
        Set<X509Certificate> trusted = loadTrustedCerts();

        cades.verify(trusted, crls);

        CAdESSigner[] signers = cades.getCAdESSignerInfos();
        Map<String, Object> result = new HashMap<>();
        result.put("valid", true);
        result.put("signersCount", signers.length);

        StringBuilder fullInfo = new StringBuilder();
        String firstCn = "";
        String firstSerial = "";
        for (int i = 0; i < signers.length; i++) {
            X509Certificate cert = signers[i].getSignerCertificate();
            if (cert != null) {
                String fullDn = cert.getSubjectX500Principal().getName();
                String cn = extractCn(fullDn);
                String serial = cert.getSerialNumber().toString(16);
                fullInfo.append(fullDn).append("; serial=").append(serial).append("\n");
                if (i == 0) {
                    firstCn = cn;
                    firstSerial = serial;
                }
            }
        }
        result.put("signersInfo", fullInfo.toString());
        result.put("signerSubject", firstCn);
        result.put("signerSerial", firstSerial);

        lastCn.set(firstCn);
        return result;
    }

    /**
     * Загружает доверенные сертификаты из:
     *  1. /app/certs/*.cer, *.crt (корни УЦ, промежуточные)
     *  2. $JAVA_HOME/lib/security/cacerts (стандартный Java truststore)
     */
    private Set<X509Certificate> loadTrustedCerts() {
        Set<X509Certificate> result = new HashSet<>();
        CertificateFactory cf;
        try {
            cf = CertificateFactory.getInstance("X.509");
        } catch (Exception e) {
            return result;
        }

        // 1. /app/certs
        File certsDir = new File("/app/certs");
        if (certsDir.isDirectory()) {
            File[] files = certsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.isFile()) continue;
                    try (InputStream is = new FileInputStream(f)) {
                        Certificate c = cf.generateCertificate(is);
                        if (c instanceof X509Certificate) {
                            result.add((X509Certificate) c);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // 2. Java cacerts
        try (InputStream is = new FileInputStream(
                System.getProperty("java.home") + "/lib/security/cacerts")) {
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(is, "changeit".toCharArray());
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String a = aliases.nextElement();
                Certificate c = ks.getCertificate(a);
                if (c instanceof X509Certificate) {
                    result.add((X509Certificate) c);
                }
            }
        } catch (Exception ignored) {}

        return result;
    }

    /** CN подписанта из последней проверенной подписи (в этом потоке). */
    public String extractCnFromLastSignature() {
        return lastCn.get();
    }

    public String extractCn(String x500) {
        if (x500 == null) return "";
        try {
            LdapName ln = new LdapName(x500);
            for (Rdn rdn : ln.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return String.valueOf(rdn.getValue());
                }
            }
        } catch (Exception ignored) {}
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("CN=([^,]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(x500);
        return m.find() ? m.group(1).replace("\"", "").trim() : x500;
    }

    /**
     * Читает все *.crl из каталога. Возвращает Set — если какой-то файл
     * не парсится, он логируется как warning, но не валит всю проверку.
     */
    private Set<X509CRL> loadAllCrls(Path crlDir) {
        Set<X509CRL> result = new HashSet<>();
        if (crlDir == null || !Files.isDirectory(crlDir)) {
            System.out.println("[SignatureService] CRL dir not found: " + crlDir);
            return result;
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            try (var files = Files.list(crlDir)) {
                files.filter(f -> f.toString().endsWith(".crl"))
                     .sorted()
                     .forEach(f -> {
                         try (var is = Files.newInputStream(f)) {
                             result.add((X509CRL) cf.generateCRL(is));
                         } catch (Exception e) {
                             System.out.println("[SignatureService] CRL parse failed: "
                                     + f.getFileName() + " — " + e.getMessage());
                         }
                     });
            }
        } catch (Exception e) {
            System.out.println("[SignatureService] CRL dir read error: " + e.getMessage());
        }
        return result;
    }
}
