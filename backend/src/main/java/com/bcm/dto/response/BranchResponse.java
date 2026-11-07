package com.bcm.dto.response;

import java.util.UUID;
public record BranchResponse(UUID id, String code, String name, String district, String address, long courtCount) { }
