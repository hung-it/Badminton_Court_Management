package com.bcm.repository;

import com.bcm.entity.Category;

import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Category c where c.id=:id")
    Optional<Category> findForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    List<Category> findByDeletedAtIsNullOrderByCategoryNameAsc();

    Optional<Category> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByCategoryNameIgnoreCaseAndDeletedAtIsNullAndIdNot(String categoryName, UUID id);
}
