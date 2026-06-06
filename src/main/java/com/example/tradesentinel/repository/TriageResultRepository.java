package com.example.tradesentinel.repository;

import com.example.tradesentinel.entity.TriageResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TriageResultRepository extends JpaRepository<TriageResultEntity, Long> {
    Optional<TriageResultEntity> findByAlertId(String alertId);
    List<TriageResultEntity> findTop50ByOrderByTriagedAtDesc();

    @Query("SELECT t.verdict, COUNT(t) FROM TriageResultEntity t GROUP BY t.verdict")
    List<Object[]> countByVerdict();

    @Query("SELECT t.source, COUNT(t) FROM TriageResultEntity t GROUP BY t.source")
    List<Object[]> countBySource();
}
