package com.bcm.repository;

import com.bcm.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Role entity
 */
@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

    /**
     * Find role by name
     * @param roleName Role name (ADMIN, STAFF, CUSTOMER)
     * @return Optional Role
     */
    Optional<Role> findByRoleName(String roleName);

    /**
     * Check if role exists by name
     * @param roleName Role name
     * @return true if exists
     */
    Boolean existsByRoleName(String roleName);
}
