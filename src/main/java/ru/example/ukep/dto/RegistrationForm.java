package ru.example.ukep.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class RegistrationForm {

    @NotBlank(message = "Укажите email")
    @Email(message = "Некорректный email")
    private String email;

    @NotBlank(message = "Укажите ФИО")
    @Size(min = 3, max = 150, message = "ФИО от 3 до 150 символов")
    private String fullName;

    @NotBlank(message = "Укажите телефон")
    @Pattern(regexp = "^\\+?[0-9\\-\\s()]{7,20}$", message = "Некорректный телефон")
    private String phone;

    @NotBlank(message = "Введите пароль")
    @Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=;:'\",.<>/?\\\\|`~\\[\\]{}]).{8,16}$",
        message = "Пароль: 8-16 символов, буквы, цифры и минимум один спецсимвол"
    )
    private String password;

    @NotBlank(message = "Повторите пароль")
    private String passwordConfirm;

    // getters/setters
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getPasswordConfirm() { return passwordConfirm; }
    public void setPasswordConfirm(String passwordConfirm) { this.passwordConfirm = passwordConfirm; }
}
