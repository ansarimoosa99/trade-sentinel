package com.example.tradesentinel.repository;

import com.example.tradesentinel.entity.ComplianceCaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComplianceCaseRepository extends JpaRepository<ComplianceCaseEntity, Long> {
    Optional<ComplianceCaseEntity> findByCaseId(String caseId);
    Optional<ComplianceCaseEntity> findByAlertId(String alertId);
    List<ComplianceCaseEntity> findTop50ByOrderByCreatedAtDesc();
    List<ComplianceCaseEntity> findByStatus(String status);

    @Query("SELECT c.status, COUNT(c) FROM ComplianceCaseEntity c GROUP BY c.status")
    List<Object[]> countByStatus();
}
