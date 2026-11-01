package com.example.meetplatform.scheduling.repository;

import com.example.meetplatform.scheduling.entity.AvailabilityRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface AvailabilityRuleRepository extends JpaRepository<AvailabilityRule, UUID> {

    List<AvailabilityRule> findByUserIdOrderByDayOfWeekAscStartTimeAsc(UUID userId);

    @Modifying
    @Query("delete from AvailabilityRule r where r.userId = :userId")
    void deleteByUserId(UUID userId);
}
