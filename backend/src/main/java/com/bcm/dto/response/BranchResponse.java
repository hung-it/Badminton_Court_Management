package com.bcm.dto.response;

import java.util.UUID;

public record BranchResponse(
        UUID id,
        String code,
        String name,
        String district,
        String address,
        boolean active,
        String status,
        long courtCount
) {
    public BranchResponse(UUID id, String code, String name, String district, String address, boolean active, long courtCount) {
        this(id, code, name, district, address, active, active ? "AVAILABLE" : "CLOSED", courtCount);
    }

    public BranchResponse(UUID id, String code, String name, String district, String address, long courtCount) {
        this(id, code, name, district, address, true, "AVAILABLE", courtCount);
    }
}
