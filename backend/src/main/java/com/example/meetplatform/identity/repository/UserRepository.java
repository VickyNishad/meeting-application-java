package com.example.meetplatform.identity.repository;

import com.example.meetplatform.identity.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(String email);

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(String email);

    /** {@code pattern} must already be lower-cased and wrapped in '%'. */
    @Query("select u from User u where lower(u.email) like :pattern or lower(u.displayName) like :pattern")
    Page<User> search(String pattern, Pageable pageable);

    List<User> findByIdIn(Collection<UUID> ids);
}
