package com.bcm.repository;

import com.bcm.entity.InvoiceDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InvoiceDetailRepository extends JpaRepository<InvoiceDetail, UUID> {
    List<InvoiceDetail> findByInvoiceId(UUID invoiceId);
    List<InvoiceDetail> findByInvoiceIdIn(List<UUID> invoiceIds);
}