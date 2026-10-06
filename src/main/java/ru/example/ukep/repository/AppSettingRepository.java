package ru.example.ukep.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.ukep.entity.AppSetting;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
