package com.example.tradesentinel.repository;

import com.example.tradesentinel.entity.SurveillanceAlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface SurveillanceAlertRepository extends JpaRepository<SurveillanceAlertEntity, Long> {
    Optional<SurveillanceAlertEntity> findByAlertId(String alertId);
    List<SurveillanceAlertEntity> findByTraderId(String traderId);
    List<SurveillanceAlertEntity> findBySeverity(String severity);
    List<SurveillanceAlertEntity> findTop50ByOrderByCreatedAtDesc();

    @Query("SELECT a.severity, COUNT(a) FROM SurveillanceAlertEntity a GROUP BY a.severity")
    List<Object[]> countBySeverity();

    @Query("SELECT a.pattern, COUNT(a) FROM SurveillanceAlertEntity a GROUP BY a.pattern")
    List<Object[]> countByPattern();
}
