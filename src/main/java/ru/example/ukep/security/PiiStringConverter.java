package ru.example.ukep.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * JPA-конвертер: прозрачно шифрует строку при записи в БД
 * и расшифровывает при чтении. Используется в @Convert на PII-полях.
 *
 * Регистрируется как Spring-бин (@Component) — в Spring Boot 3 + Hibernate 6
 * LocalContainerEntityManagerFactoryBean подключает SpringBeanContainer,
 * поэтому Hibernate берёт инстанс конвертера из Spring-контекста,
 * а не создаёт его сам. Это позволяет использовать обычный
 * constructor injection без статических полей и @Autowired-сеттера
 * (Sonar java:S2696).
 */
@Component
@Converter
public class PiiStringConverter implements AttributeConverter<String, String> {

    private final PiiEncryptor encryptor;

    public PiiStringConverter(PiiEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        return encryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        return encryptor.decrypt(dbData);
    }
}
