package ru.example.ukep.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JPA-конвертер: прозрачно шифрует строку при записи в БД
 * и расшифровывает при чтении. Используется в @Convert на PII-полях.
 */
@Converter
@Component
public class PiiStringConverter implements AttributeConverter<String, String> {

    private static PiiEncryptor encryptor;

    @Autowired
    public void setEncryptor(PiiEncryptor e) {
        PiiStringConverter.encryptor = e;
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
