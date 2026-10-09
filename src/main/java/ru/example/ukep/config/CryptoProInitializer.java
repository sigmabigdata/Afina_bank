package ru.example.ukep.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Security;

@Component
public class CryptoProInitializer {

    private static final Logger log = LoggerFactory.getLogger(CryptoProInitializer.class);

    /** Флаг для отключения CryptoPro в средах, где он не установлен. */
    @Value("${crypto.pro.enabled:true}")
    private boolean enabled;

    @PostConstruct
    public void init() {
        System.setProperty("file.encoding", "UTF-8");

        if (!enabled) {
            log.warn("CryptoPro отключён через crypto.pro.enabled=false");
            log.warn("Проверка подписи работать не будет. Это допустимо в dev/test.");
            return;
        }

        // ============================================================
        // Системные свойства для проверки отзыва (CRL/OCSP).
        // БЕЗ них CAdES падает с "Could not determine revocation status".
        // ============================================================
        System.setProperty("com.sun.security.enableCRLDP", "true");
        System.setProperty("com.ibm.security.enableCRLDP", "true");
        System.setProperty("ocsp.enable", "true");
        System.setProperty("com.sun.security.enableAIAcaIssuers", "true");
        System.setProperty("ru.CryptoPro.reprov.enableAIAcaIssuers", "true");
        System.setProperty("ru.CryptoPro.reprov.enableCRLDP", "true");

        log.info("Установлены системные свойства для проверки CRL/OCSP");

        try {
            // 1. Провайдер JCSP
            Class<?> jcspCls = Class.forName("ru.CryptoPro.JCSP.JCSP");
            Object provider = jcspCls.getDeclaredConstructor().newInstance();
            String name = (String) jcspCls.getField("PROVIDER_NAME").get(null);
            if (Security.getProvider(name) == null) {
                Security.addProvider((java.security.Provider) provider);
                log.info("CryptoPro JCSP provider registered: {}", name);
            }

            // 2. Провайдер RevCheck (правильный класс — ru.CryptoPro.reprov.RevCheck)
            try {
                Class<?> revCls = Class.forName("ru.CryptoPro.reprov.RevCheck");
                if (Security.getProvider("RevCheck") == null) {
                    Security.addProvider(
                            (java.security.Provider) revCls.getDeclaredConstructor().newInstance());
                    log.info("CryptoPro RevCheck provider registered");
                }
            } catch (Exception t) {
                log.warn("RevCheck не зарегистрирован: {}", t.getMessage());
            }

            // 3. CAdES default provider
            try {
                Class<?> cadesConfig = Class.forName("ru.CryptoPro.CAdES.CAdESConfig");
                cadesConfig.getMethod("setDefaultProvider", String.class)
                        .invoke(null, name);
                log.info("CAdES default provider set to {}", name);

                // Отключаем нативную проверку, чтобы использовался
                // Java CertPathBuilder с нашим набором trusted-сертификатов.
                // Пробуем все возможные системные свойства.
                String[] props = {
                    "ru.CryptoPro.CAdES.useNativeVerify",
                    "ru.CryptoPro.CAdES.nativeVerify",
                    "ru.CryptoPro.CAdES.useNative",
                    "ru.CryptoPro.AdES.useNativeImpl",
                    "ru.CryptoPro.AdES.nativeVerify",
                    "ru.CryptoPro.AdES.useNative",
                };
                for (String p : props) System.setProperty(p, "false");
                System.setProperty("ru.CryptoPro.AdES.useBuiltinImpl", "true");
                System.setProperty("ru.CryptoPro.CAdES.disableNativeVerify", "true");

                // Плюс reflection: ищем методы в *Config классах
                String[] cfgClasses = {
                    "ru.CryptoPro.CAdES.CAdESConfig",
                    "ru.CryptoPro.AdES.AdESConfig",
                    "ru.CryptoPro.AdES.config.AdESConfig",
                };
                String[] methods = {
                    "setUseNativeVerify", "setNativeVerify",
                    "setUseBuiltinVerify", "setBuiltinVerify",
                    "setUseNativeImpl", "setNativeImpl",
                };
                boolean done = false;
                for (String c : cfgClasses) {
                    try {
                        Class<?> cls = Class.forName(c);
                        for (String m : methods) {
                            try {
                                cls.getMethod(m, boolean.class).invoke(null, false);
                                log.info("{}#{}(false) — OK", c, m);
                                done = true;
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }
                if (!done) {
                    log.warn("Reflection setUseNativeVerify: ни один метод не найден, "
                           + "полагаемся на системные свойства");
                }
                log.info("CAdES native verify disabled (attempted)");
            } catch (Exception t) {
                log.warn("CAdESConfig не настроен: {}", t.getMessage());
            }

        } catch (Exception t) {
            log.warn("CryptoPro провайдеры не зарегистрированы. " +
                    "Проверка подписи не будет работать. Причина: {}", t.getMessage());
        }
    }
}
