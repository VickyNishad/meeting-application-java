package com.example.meetplatform.identity.repository;

import com.example.meetplatform.identity.entity.UserCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserCredentialRepository extends JpaRepository<UserCredential, UUID> {
}
