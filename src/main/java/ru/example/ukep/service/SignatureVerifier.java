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
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SignatureVerifier {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final ThreadLocal<String> lastCn = new ThreadLocal<>();

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

        Map<String, Object> result = new HashMap<>();
        List<String> infos = new ArrayList<>();
        String firstCn = "";
        String firstSerial = "";

        for (SignerInformation signer : signers.getSigners()) {
            X509Certificate cert = getSignerCert(signer, cms);
            boolean valid;
            try {
                valid = signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                        .setProvider("BC")
                        .build(cert));
            } catch (Exception e) {
                throw new RuntimeException("Ошибка проверки подписи: " + e.getMessage(), e);
            }
            if (!valid) {
                throw new RuntimeException("Подпись недействительна");
            }

            String subject = cert.getSubjectX500Principal().getName();
            String cn = extractCn(subject);
            String serial = cert.getSerialNumber().toString(16);
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
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            File dir = new File("/app/certs");
            if (dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (!f.isFile()) continue;
                        try (InputStream is = new FileInputStream(f)) {
                            result.add((X509Certificate) cf.generateCertificate(is));
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private String extractCn(String x500) {
        if (x500 == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("CN=([^,]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(x500);
        return m.find() ? m.group(1).replace("\"", "").trim() : x500;
    }
}
