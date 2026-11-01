package com.example.meetplatform.scheduling.repository;

import com.example.meetplatform.scheduling.entity.AvailabilityException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AvailabilityExceptionRepository extends JpaRepository<AvailabilityException, UUID> {

    List<AvailabilityException> findByUserIdAndDateBetweenOrderByDateAscStartTimeAsc(UUID userId, LocalDate from, LocalDate to);
}
