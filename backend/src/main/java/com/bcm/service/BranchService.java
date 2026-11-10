package com.bcm.service;

import com.bcm.dto.response.BranchResponse;
import com.bcm.entity.Court;
import com.bcm.entity.CourtStatus;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.BranchRepository;
import com.bcm.repository.CourtRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BranchService {
    private final BranchRepository branches;
    private final CourtRepository courts;

    public List<BranchResponse> list() {
        var counts = courts.findByDeletedAtIsNullOrderByCourtCodeAsc().stream()
            .filter(c -> c.getBranchId() != null && c.getStatus() == CourtStatus.AVAILABLE)
            .collect(Collectors.groupingBy(Court::getBranchId, Collectors.counting()));
        return branches.findByActiveTrueAndDeletedAtIsNullOrderByCodeAsc().stream()
            .map(b -> new BranchResponse(b.getId(), b.getCode(), b.getName(), b.getDistrict(), b.getAddress(), counts.getOrDefault(b.getId(), 0L)))
            .toList();
    }

    public List<Court> courts(UUID branchId) {
        if (!branches.existsByIdAndActiveTrueAndDeletedAtIsNull(branchId)) throw new ResourceNotFoundException("Branch not found");
        return courts.findByBranchIdAndDeletedAtIsNullOrderByBranchCourtNumberAsc(branchId).stream()
            .filter(c -> c.getStatus() == CourtStatus.AVAILABLE).toList();
    }
}
