package com.bcm.repository;

import com.bcm.entity.Branch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface BranchRepository extends JpaRepository<Branch, UUID> {
    List<Branch> findByActiveTrueAndDeletedAtIsNullOrderByCodeAsc();
    boolean existsByIdAndActiveTrueAndDeletedAtIsNull(UUID id);
}
