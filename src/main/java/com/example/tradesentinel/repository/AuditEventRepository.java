package com.example.tradesentinel.repository;

import com.example.tradesentinel.entity.AuditEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {
    List<AuditEventEntity> findTop100ByOrderByEventTimeDesc();
    List<AuditEventEntity> findByAlertIdOrderByEventTimeDesc(String alertId);

    @Query("SELECT a FROM AuditEventEntity a WHERE " +
           "(:eventType = '' OR a.eventType LIKE CONCAT('%', :eventType, '%')) AND " +
           "(:search = '' OR LOWER(COALESCE(a.description, '')) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "  OR LOWER(COALESCE(a.alertId, '')) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "  OR LOWER(COALESCE(a.actor, '')) LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "ORDER BY a.eventTime DESC")
    Page<AuditEventEntity> findFiltered(
            @Param("eventType") String eventType,
            @Param("search") String search,
            Pageable pageable);
}
