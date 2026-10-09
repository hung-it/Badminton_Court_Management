package com.bcm.repository;
import com.bcm.entity.Product;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ProductRepository extends JpaRepository<Product,UUID> {
List<Product> findByDeletedAtIsNullOrderByNameAsc();
Optional<Product> findByIdAndDeletedAtIsNull(UUID id);
boolean existsByCategoryIdAndDeletedAtIsNull(UUID categoryId);
}
