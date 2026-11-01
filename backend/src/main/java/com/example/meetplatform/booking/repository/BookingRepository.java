package com.example.meetplatform.booking.repository;

import com.example.meetplatform.booking.entity.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByManageTokenHash(String manageTokenHash);

    @Query("""
            select b from Booking b where b.hostId = :hostId
              and b.status = com.example.meetplatform.booking.entity.Booking.Status.CONFIRMED
              and b.blockedStart < :to and b.blockedEnd > :from
            """)
    List<Booking> findConfirmedOverlapping(UUID hostId, Instant from, Instant to);

    @Query("select b from Booking b where b.hostId = :hostId and b.startTime >= :now order by b.startTime asc")
    Page<Booking> findUpcoming(UUID hostId, Instant now, Pageable pageable);

    @Query("select b from Booking b where b.hostId = :hostId and b.startTime < :now order by b.startTime desc")
    Page<Booking> findPast(UUID hostId, Instant now, Pageable pageable);

    long countByCreatedAtGreaterThanEqual(Instant since);

    boolean existsByBookingPageIdAndStatusAndEndTimeAfter(UUID bookingPageId, Booking.Status status, Instant now);
}
