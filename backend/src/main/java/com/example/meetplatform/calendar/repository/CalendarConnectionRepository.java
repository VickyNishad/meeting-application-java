package com.example.meetplatform.calendar.repository;

import com.example.meetplatform.calendar.entity.CalendarConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarConnectionRepository extends JpaRepository<CalendarConnection, UUID> {

    List<CalendarConnection> findByUserId(UUID userId);

    Optional<CalendarConnection> findByUserIdAndProvider(UUID userId, String provider);

    Optional<CalendarConnection> findFirstByUserIdAndStatus(UUID userId, String status);
}
