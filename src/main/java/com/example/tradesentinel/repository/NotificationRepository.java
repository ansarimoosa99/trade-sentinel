package com.example.tradesentinel.repository;

import com.example.tradesentinel.entity.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {
    Optional<NotificationEntity> findByNotificationId(String notificationId);
    List<NotificationEntity> findTop50ByOrderByCreatedAtDesc();
    List<NotificationEntity> findByAlertId(String alertId);
    List<NotificationEntity> findByStatus(String status);
}
