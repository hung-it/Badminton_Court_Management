package com.bcm.repository;

import com.bcm.entity.ImportOrderDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ImportOrderDetailRepository extends JpaRepository<ImportOrderDetail, UUID> {

    List<ImportOrderDetail> findAllByImportOrderId(UUID importOrderId);

    void deleteAllByImportOrderId(UUID importOrderId);
}