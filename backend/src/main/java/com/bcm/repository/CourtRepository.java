package com.bcm.repository;

import com.bcm.entity.Court;

import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface CourtRepository extends JpaRepository<Court, UUID> {
    List<Court> findByDeletedAtIsNullOrderByCourtNumberAsc();

    Optional<Court> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByCourtNumberAndIdNot(Integer courtNumber, UUID id);
}
