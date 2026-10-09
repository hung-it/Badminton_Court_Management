package com.bcm.repository;
import com.bcm.entity.Category;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface CategoryRepository extends JpaRepository<Category,UUID> {
List<Category> findByDeletedAtIsNullOrderByCategoryNameAsc();
Optional<Category> findByIdAndDeletedAtIsNull(UUID id);
boolean existsByCategoryNameIgnoreCaseAndDeletedAtIsNullAndIdNot(String categoryName, UUID id);
}
