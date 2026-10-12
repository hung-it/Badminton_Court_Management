package com.bcm.repository;

import com.bcm.entity.Product;
import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    List<Product> findByDeletedAtIsNullOrderByNameAsc();

    Optional<Product> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByCategoryIdAndDeletedAtIsNull(UUID categoryId);

    default List<Product> findAllByDeletedAtIsNullOrderByNameAsc() {
        return findByDeletedAtIsNullOrderByNameAsc();
    }
}
