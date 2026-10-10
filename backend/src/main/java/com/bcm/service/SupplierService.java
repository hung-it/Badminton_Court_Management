package com.bcm.service;

import com.bcm.dto.request.SupplierRequest;
import com.bcm.entity.Supplier;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplierService {

    private final SupplierRepository supplierRepository;

    public List<Supplier> findAll() {
        return supplierRepository.findAllByDeletedAtIsNullOrderByNameAsc();
    }

    public Supplier findById(UUID id) {
        return supplierRepository.findById(id)
                .filter(supplier -> supplier.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", "id", id));
    }

    @Transactional
    public Supplier create(SupplierRequest request) {
        Supplier supplier = Supplier.builder()
                .name(request.getName().trim())
                .phone(request.getPhone())
                .address(request.getAddress())
                .build();
        return supplierRepository.save(supplier);
    }

    @Transactional
    public Supplier update(UUID id, SupplierRequest request) {
        Supplier supplier = findById(id);
        supplier.setName(request.getName().trim());
        supplier.setPhone(request.getPhone());
        supplier.setAddress(request.getAddress());
        return supplierRepository.save(supplier);
    }

    @Transactional
    public void delete(UUID id) {
        Supplier supplier = findById(id);
        supplier.softDelete();
        supplierRepository.save(supplier);
    }
}