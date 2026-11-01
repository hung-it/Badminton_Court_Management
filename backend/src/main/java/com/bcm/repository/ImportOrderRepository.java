package com.bcm.repository;

import com.bcm.entity.ImportOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ImportOrderRepository extends JpaRepository<ImportOrder, UUID> {

    @org.springframework.data.jpa.repository.Query("select coalesce(sum(o.totalAmount),0) from ImportOrder o where o.status <> 'CANCELLED' and o.importDate >= :from and o.importDate < :to")
    java.math.BigDecimal sumExpense(@org.springframework.data.repository.query.Param("from") java.time.LocalDate from,
                                   @org.springframework.data.repository.query.Param("to") java.time.LocalDate to);

    List<ImportOrder> findAllByStatusNotOrderByImportDateDesc(String status);

    List<ImportOrder> findAllByOrderByImportDateDesc();

    default List<ImportOrder> findAllByDeletedAtIsNullOrderByImportDateDesc() {
        return findAllByStatusNotOrderByImportDateDesc("CANCELLED");
    }

    Optional<ImportOrder> findByIdAndStatusNot(UUID id, String status);

    default Optional<ImportOrder> findByIdAndDeletedAtIsNull(UUID id) {
        return findByIdAndStatusNot(id, "CANCELLED");
    }
}
