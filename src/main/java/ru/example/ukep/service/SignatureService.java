package ru.example.ukep.service;

import org.springframework.stereotype.Service;
import ru.CryptoPro.CAdES.CAdESSignature;
import ru.CryptoPro.CAdES.CAdESSigner;
import ru.CryptoPro.CAdES.CAdESType;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
public class SignatureService {

    public Map<String, Object> verifyDetached(Path contentPath,
                                              String signatureBase64,
                                              Path crlPath) throws Exception {
        byte[] data = Files.readAllBytes(contentPath);

        String cleaned = signatureBase64
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s+", "")
                .replaceAll("[^A-Za-z0-9+/=]", "");

        byte[] signatureBytes = Base64.getDecoder().decode(cleaned);

        CAdESSignature cades = new CAdESSignature(signatureBytes, data, CAdESType.CAdES_BES);

        // Загружаем CRL и передаём его в verify для offline-проверки
        Set<X509CRL> crls = loadCrl(crlPath);
        cades.verify(null, crls);

        CAdESSigner[] signers = cades.getCAdESSignerInfos();
        Map<String, Object> result = new HashMap<>();
        result.put("valid", true);
        result.put("signersCount", signers.length);

        StringBuilder sb = new StringBuilder();
        for (CAdESSigner s : signers) {
            X509Certificate cert = s.getSignerCertificate();
            if (cert != null) {
                sb.append(cert.getSubjectX500Principal().getName())
                  .append("; serial=").append(cert.getSerialNumber())
                  .append("\n");
            }
        }
        result.put("signersInfo", sb.toString());
        return result;
    }

    private Set<X509CRL> loadCrl(Path crlPath) throws Exception {
        try (FileInputStream crlStream = new FileInputStream(crlPath.toFile())) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509CRL crl = (X509CRL) cf.generateCRL(crlStream);
            return Collections.singleton(crl);
        }
    }
}
