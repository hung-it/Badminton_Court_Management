package com.bcm.repository;
import com.bcm.entity.Court;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface CourtRepository extends JpaRepository<Court,UUID> {
List<Court> findByDeletedAtIsNullOrderByCourtNumberAsc();
Optional<Court> findByIdAndDeletedAtIsNull(UUID id);
boolean existsByCourtNumberAndIdNot(Integer courtNumber, UUID id);
}
