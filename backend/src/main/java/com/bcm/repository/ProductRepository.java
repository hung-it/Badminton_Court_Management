package com.bcm.repository;

import com.bcm.entity.Product;
import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id=:id")
    Optional<Product> findByIdForPriceApproval(@org.springframework.data.repository.query.Param("id") UUID id);
    List<Product> findByDeletedAtIsNullOrderByNameAsc();

    Optional<Product> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByCategoryIdAndDeletedAtIsNull(UUID categoryId);

    default List<Product> findAllByDeletedAtIsNullOrderByNameAsc() {
        return findByDeletedAtIsNullOrderByNameAsc();
    }
}
