package com.example.meetplatform.booking.repository;

import com.example.meetplatform.booking.entity.BookingPage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingPageRepository extends JpaRepository<BookingPage, UUID> {

    Optional<BookingPage> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<BookingPage> findByOwnerIdOrderByCreatedAtAsc(UUID ownerId);

    /** Conflicts with the key-share lock a concurrent booking insert takes on its page (foreign key check). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from BookingPage p where p.id = :id")
    Optional<BookingPage> findByIdForUpdate(UUID id);
}
