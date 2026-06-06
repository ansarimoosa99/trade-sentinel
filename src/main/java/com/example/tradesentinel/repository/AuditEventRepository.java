package com.example.tradesentinel.repository;

import com.example.tradesentinel.entity.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {
    List<AuditEventEntity> findTop100ByOrderByEventTimeDesc();
    List<AuditEventEntity> findByAlertIdOrderByEventTimeDesc(String alertId);
}
