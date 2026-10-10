package com.bcm.repository;

import com.bcm.entity.ImportOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ImportOrderRepository extends JpaRepository<ImportOrder, UUID> {

    List<ImportOrder> findAllByDeletedAtIsNullOrderByImportDateDesc();

    java.util.Optional<ImportOrder> findByIdAndDeletedAtIsNull(UUID id);
}