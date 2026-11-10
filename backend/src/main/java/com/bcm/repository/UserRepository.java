package com.bcm.repository;

import com.bcm.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for User entity
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id=:id and u.deletedAt is null")
    Optional<User> findActiveForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    /**
     * Find user by email
     * @param email User email
     * @return Optional User
     */
    Optional<User> findByEmail(String email);

    /**
     * Check if email already exists
     * @param email Email to check
     * @return true if exists
     */
    Boolean existsByEmail(String email);

    /**
     * Find user by email and not deleted
     * @param email User email
     * @return Optional User
     */
    Optional<User> findByEmailAndDeletedAtIsNull(String email);
}
