package ru.example.ukep.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.CryptoPro.CAdES.CAdESConfig;
import ru.CryptoPro.JCSP.JCSP;

import java.security.Security;

@Component
public class CryptoProInitializer {

    private static final Logger log = LoggerFactory.getLogger(CryptoProInitializer.class);

    @PostConstruct
    public void init() {
        System.setProperty("file.encoding", "UTF-8");
        try {
            // 1. Провайдер JCSP
            if (Security.getProvider(JCSP.PROVIDER_NAME) == null) {
                Security.addProvider(new JCSP());
                log.info("CryptoPro JCSP provider registered: {}", JCSP.PROVIDER_NAME);
            }

            // 2. Провайдер RevCheck — правильный класс: ru.CryptoPro.reprov.RevCheck
            if (Security.getProvider("RevCheck") == null) {
                Class<?> revCls = Class.forName("ru.CryptoPro.reprov.RevCheck");
                Security.addProvider((java.security.Provider) revCls.getDeclaredConstructor().newInstance());
                log.info("CryptoPro RevCheck provider registered");
            }

            // 3. Устанавливаем JCSP как провайдер по умолчанию для CAdES.
            //    Без этого CAdES пытается использовать JCP и падает с "no such provider: RevCheck".
            CAdESConfig.setDefaultProvider(JCSP.PROVIDER_NAME);
            log.info("CAdES default provider set to {}", JCSP.PROVIDER_NAME);

            // Включаем онлайн-проверку отзыва по CRL (для Oracle JDK)
            System.setProperty("com.sun.security.enableCRLDP", "true");
// Для IBM JDK
            System.setProperty("com.ibm.security.enableCRLDP", "true");
// Включаем онлайн-проверку через OCSP
            System.setProperty("ocsp.enable", "true");
// Разрешаем автоматическую загрузку сертификатов УЦ
            System.setProperty("com.sun.security.enableAIAcaIssuers", "true");
            System.setProperty("ru.CryptoPro.reprov.enableAIAcaIssuers", "true");

        } catch (Throwable t) {
            log.warn("CryptoPro провайдеры не зарегистрированы. Причина: {}", t.getMessage(), t);
        }
    }
}
