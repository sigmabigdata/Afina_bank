package ru.example.ukep.dto;

public class CertLoginRequest {
    private String cn;
    private String snils;
    private String challenge;      // случайная строка, сгенерированная сервером
    private String signatureBase64; // подпись челленджа, CAdES-BES detached

    public String getCn() { return cn; }
    public void setCn(String cn) { this.cn = cn; }
    public String getSnils() { return snils; }
    public void setSnils(String snils) { this.snils = snils; }
    public String getChallenge() { return challenge; }
    public void setChallenge(String challenge) { this.challenge = challenge; }
    public String getSignatureBase64() { return signatureBase64; }
    public void setSignatureBase64(String signatureBase64) { this.signatureBase64 = signatureBase64; }
}
