package com.example.tradesentinel.repository;

import com.example.tradesentinel.entity.WatchlistEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WatchlistEntryRepository extends JpaRepository<WatchlistEntryEntity, Long> {
    Optional<WatchlistEntryEntity> findByTraderId(String traderId);
    List<WatchlistEntryEntity> findByStatus(String status);
    List<WatchlistEntryEntity> findAllByOrderByRiskScoreDesc();
    boolean existsByTraderId(String traderId);
}
