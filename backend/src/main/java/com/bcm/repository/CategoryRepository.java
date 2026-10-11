package com.bcm.repository;

import com.bcm.entity.Category;

import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByDeletedAtIsNullOrderByCategoryNameAsc();

    Optional<Category> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByCategoryNameIgnoreCaseAndDeletedAtIsNullAndIdNot(String categoryName, UUID id);
}
