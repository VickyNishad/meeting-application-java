package com.example.meetplatform.scheduling.repository;

import com.example.meetplatform.scheduling.entity.AvailabilitySchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AvailabilityScheduleRepository extends JpaRepository<AvailabilitySchedule, UUID> {
}
