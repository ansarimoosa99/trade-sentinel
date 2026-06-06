package com.example.tradesentinel.repository;

import com.example.tradesentinel.entity.OrderEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface OrderEventRepository extends JpaRepository<OrderEventEntity, Long> {
    List<OrderEventEntity> findTop200ByOrderByEventTimeDesc();
    List<OrderEventEntity> findByTraderIdOrderByEventTimeDesc(String traderId);
    List<OrderEventEntity> findByEventTimeAfter(Instant since);
}
