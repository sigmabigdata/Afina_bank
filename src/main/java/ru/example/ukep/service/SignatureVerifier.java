package ru.example.ukep.service;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.Store;
import org.bouncycastle.util.encoders.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Верификатор CAdES-BES подписей через BouncyCastle с ручным
 * построением цепочки сертификатов и проверкой отзыва по CRL.
 *
 * НЕ использует JCSP для chain building — это позволяет обойти
 * проблему "Root certificate is untrusted", когда JCSP ищет корни
 * в своём системном хранилище, игнорируя переданный truststore.
 */
@Service
public class SignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final ThreadLocal<String> lastCn = new ThreadLocal<>();
    private final CrlDownloader crlDownloader;
    private final Path certsDir;
    private final Path crlsDir;

    public SignatureVerifier(
            CrlDownloader crlDownloader,
            @Value("${app.certificates-path:/app/certs}") String certsPath,
            @Value("${app.crl-path:/app/crls}") String crlsPath) {
        this.crlDownloader = crlDownloader;
        this.certsDir = Paths.get(certsPath);
        this.crlsDir = Paths.get(crlsPath);
        log.info("SignatureVerifier: certs={}, crls={}", certsDir, crlsDir);
    }

    public Map<String, Object> verifyDetached(Path contentPath, String signatureBase64) throws Exception {
        byte[] data = Files.readAllBytes(contentPath);
        return verifyDetached(data, signatureBase64);
    }

    public Map<String, Object> verifyDetached(byte[] data, String signatureBase64) throws Exception {
        String cleaned = signatureBase64
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s+", "")
                .replaceAll("[^A-Za-z0-9+/=]", "");
        byte[] signatureBytes = Base64.decode(cleaned);

        CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(data), signatureBytes);
        SignerInformationStore signers = cms.getSignerInfos();

        Set<X509Certificate> truststore = loadTrustedCerts();

        // 0) Собрать все сертификаты подписантов + intermediates из CMS,
        //    затем подкачать их CRL по CDP (идемпотентно, с TTL 12ч)
        Set<X509Certificate> allSignerCerts = extractAllCerts(cms);
        try {
            int downloaded = crlDownloader.ensureCrlsFor(allSignerCerts);
            if (downloaded > 0) {
                log.info("CrlDownloader: подкачано/обновлено {} CRL", downloaded);
            }
        } catch (Exception e) {
            log.warn("CrlDownloader failed: {}", e.getMessage());
        }

        // Теперь читаем CRL из директории (там уже и свежескачанные)
        Set<X509CRL> crls = loadAllCrls();

        Map<String, Object> result = new HashMap<>();
        List<String> infos = new ArrayList<>();
        String firstCn = "";
        String firstSerial = "";

        for (SignerInformation signer : signers.getSigners()) {
            X509Certificate leaf = getSignerCert(signer, cms);

            // 1) Криптопроверка подписи (BC, fallback JCSP для ГОСТ)
            boolean valid = verifySignerCrypto(signer, leaf);
            if (!valid) {
                throw new RuntimeException("Подпись недействительна");
            }

            // 2) Срок действия листа
            leaf.checkValidity();

            // 3) Построение цепочки (вручную)
            List<X509Certificate> chain = buildChain(leaf, cms);
            log.info("Chain built: {} certificates (leaf={})", chain.size(),
                    leaf.getSubjectX500Principal());

            // 4) Проверка корня в truststore
            X509Certificate root = chain.get(chain.size() - 1);
            if (!isTrustedRoot(root, truststore)) {
                throw new RuntimeException(
                        "Корневой сертификат не в truststore: " + root.getSubjectX500Principal());
            }

            // 5) Подписи звеньев
            verifyChainSignatures(chain);

            // 6) Срок действия всех звеньев
            for (X509Certificate c : chain) {
                c.checkValidity();
            }

            // 7) Отзыв по CRL
            checkNotRevoked(chain, crls);

            // Метаданные подписанта
            String subject = leaf.getSubjectX500Principal().getName();
            String cn = extractCn(subject);
            String serial = leaf.getSerialNumber().toString(16);
            infos.add(subject + "; serial=" + serial);
            if (firstCn.isEmpty()) {
                firstCn = cn;
                firstSerial = serial;
            }
        }

        result.put("valid", true);
        result.put("signersCount", infos.size());
        result.put("signersInfo", String.join("\n", infos));
        result.put("signerSubject", firstCn);
        result.put("signerSerial", firstSerial);
        lastCn.set(firstCn);
        return result;
    }

    public String extractCnFromLastSignature() {
        return lastCn.get();
    }

    // ==================== Внутренние методы ====================

    private boolean verifySignerCrypto(SignerInformation signer, X509Certificate leaf) {
        // Пробуем BouncyCastle
        try {
            return signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider("BC").build(leaf));
        } catch (Exception bcError) {
            log.debug("BC verify failed: {}, try JCSP", bcError.getMessage());
        }
        // Fallback на JCSP (для ГОСТ, если BC не справился)
        try {
            return signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider("JCSP").build(leaf));
        } catch (Exception jcspError) {
            throw new RuntimeException("Ошибка проверки подписи: " + jcspError.getMessage(), jcspError);
        }
    }

    /** Извлекает все встроенные сертификаты из CMS (лист + промежуточные). */
    private Set<X509Certificate> extractAllCerts(CMSSignedData cms) {
        Set<X509Certificate> result = new HashSet<>();
        Store<X509CertificateHolder> store = cms.getCertificates();
        for (X509CertificateHolder holder : store.getMatches(null)) {
            try {
                result.add(new JcaX509CertificateConverter().setProvider("BC")
                        .getCertificate(holder));
            } catch (Exception ignored) {}
        }
        return result;
    }

    private X509Certificate getSignerCert(SignerInformation signer, CMSSignedData cms) throws Exception {
        Store<X509CertificateHolder> certStore = cms.getCertificates();
        Collection<X509CertificateHolder> matches = certStore.getMatches(signer.getSID());
        if (matches.isEmpty()) {
            throw new RuntimeException("Сертификат подписанта не найден в подписи");
        }
        return new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(matches.iterator().next());
    }

    private Set<X509Certificate> loadTrustedCerts() {
        Set<X509Certificate> result = new HashSet<>();
        if (!Files.isDirectory(certsDir)) {
            log.warn("truststore dir not found: {}", certsDir);
            return result;
        }
        try (Stream<Path> stream = Files.list(certsDir)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            stream.filter(Files::isRegularFile).forEach(f -> {
                String name = f.getFileName().toString().toLowerCase();
                if (!name.endsWith(".cer") && !name.endsWith(".crt") && !name.endsWith(".pem")) {
                    return;
                }
                try (InputStream is = Files.newInputStream(f)) {
                    result.add((X509Certificate) cf.generateCertificate(is));
                } catch (Exception ignored) {}
            });
        } catch (Exception e) {
            log.error("loadTrustedCerts error", e);
        }
        log.info("Truststore: {} certificates loaded from {}", result.size(), certsDir);
        return result;
    }

    private Set<X509CRL> loadAllCrls() {
        Set<X509CRL> result = new HashSet<>();
        if (!Files.isDirectory(crlsDir)) {
            log.warn("crls dir not found: {}", crlsDir);
            return result;
        }
        try (Stream<Path> stream = Files.list(crlsDir)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            stream.filter(Files::isRegularFile)
                  .filter(f -> f.getFileName().toString().endsWith(".crl"))
                  .forEach(f -> {
                      try (InputStream is = Files.newInputStream(f)) {
                          result.add((X509CRL) cf.generateCRL(is));
                      } catch (Exception e) {
                          log.debug("CRL parse failed: {} — {}", f.getFileName(), e.getMessage());
                      }
                  });
        } catch (Exception e) {
            log.error("loadAllCrls error", e);
        }
        log.info("CRLs: {} loaded from {}", result.size(), crlsDir);
        return result;
    }

    private List<X509Certificate> buildChain(X509Certificate leaf, CMSSignedData cms) throws Exception {
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(leaf);

        Set<X509Certificate> candidates = new HashSet<>();
        Store<X509CertificateHolder> certStore = cms.getCertificates();
        for (X509CertificateHolder holder : certStore.getMatches(null)) {
            try {
                candidates.add(new JcaX509CertificateConverter().setProvider("BC")
                        .getCertificate(holder));
            } catch (Exception ignored) {}
        }

        X509Certificate current = leaf;
        int maxDepth = 10;
        while (!isSelfSigned(current) && maxDepth-- > 0) {
            X509Certificate issuer = null;
            for (X509Certificate candidate : candidates) {
                if (current.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
                    if (verifySignature(current, candidate)) {
                        issuer = candidate;
                        break;
                    }
                }
            }
            if (issuer == null) {
                throw new RuntimeException(
                        "Не найден issuer для " + current.getSubjectX500Principal());
            }
            chain.add(issuer);
            current = issuer;
        }
        if (maxDepth <= 0) {
            throw new RuntimeException("Цепочка слишком длинная (>10)");
        }
        return chain;
    }

    private void verifyChainSignatures(List<X509Certificate> chain) {
        for (int i = 0; i < chain.size() - 1; i++) {
            X509Certificate cert = chain.get(i);
            X509Certificate issuer = chain.get(i + 1);
            if (!verifySignature(cert, issuer)) {
                throw new RuntimeException(
                        "Подпись звена невалидна: " + cert.getSubjectX500Principal()
                        + " ← " + issuer.getSubjectX500Principal());
            }
        }
    }

    private boolean verifySignature(X509Certificate cert, X509Certificate issuer) {
        try {
            cert.verify(issuer.getPublicKey(), "BC");
            return true;
        } catch (Exception bcError) {
            try {
                cert.verify(issuer.getPublicKey(), "JCSP");
                return true;
            } catch (Exception jcspError) {
                return false;
            }
        }
    }

    private boolean isSelfSigned(X509Certificate cert) {
        return cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
    }

    private boolean isTrustedRoot(X509Certificate root, Set<X509Certificate> truststore) {
        for (X509Certificate trusted : truststore) {
            if (root.getSubjectX500Principal().equals(trusted.getSubjectX500Principal())) {
                try {
                    if (Arrays.equals(
                            root.getPublicKey().getEncoded(),
                            trusted.getPublicKey().getEncoded())) {
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private void checkNotRevoked(List<X509Certificate> chain, Set<X509CRL> crls) {
        for (int i = 0; i < chain.size() - 1; i++) {
            X509Certificate cert = chain.get(i);
            X509CRL matchingCrl = null;
            for (X509CRL crl : crls) {
                if (crl.getIssuerX500Principal().equals(cert.getIssuerX500Principal())) {
                    matchingCrl = crl;
                    break;
                }
            }
            if (matchingCrl == null) {
                log.warn("Нет CRL для {} — проверка отзыва пропущена",
                        cert.getIssuerX500Principal());
                continue;
            }
            X509CRLEntry entry = matchingCrl.getRevokedCertificate(cert.getSerialNumber());
            if (entry != null) {
                throw new RuntimeException(
                        "Сертификат отозван: " + cert.getSubjectX500Principal()
                        + ", serial=" + cert.getSerialNumber().toString(16)
                        + ", reason=" + entry.getRevocationReason());
            }
        }
    }

    private String extractCn(String x500) {
        if (x500 == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("CN=([^,]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(x500);
        return m.find() ? m.group(1).replace("\"", "").trim() : x500;
    }
}
