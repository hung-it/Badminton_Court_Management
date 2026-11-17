package com.bcm.service;

import com.bcm.dto.request.CourtBatchRequest;
import com.bcm.dto.request.CourtRequest;
import com.bcm.entity.*;
import com.bcm.exception.*;
import com.bcm.repository.BranchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CourtBatchService {
    private final BranchRepository branches;
    private final MasterDataService master;
    private final JdbcTemplate jdbc;

    @Transactional
    public List<Court> create(CourtBatchRequest r) {
        if (r.courtCount() == null || r.courtCount() < 1 || r.courtCount() > 1000)
            throw new BadRequestException("Số lượng mỗi lần thêm phải từ 1 đến 1.000 sân.");
        // Same allocation lock as single-court saves: branch creation and all courts are atomic.
        jdbc.execute("SELECT pg_advisory_xact_lock(20261117, 1)");
        UUID branchId = r.branchId();
        if (branchId == null) {
            if (r.branchName() == null || r.branchName().isBlank() || r.district() == null || r.district().isBlank() || r.address() == null || r.address().isBlank())
                throw new BadRequestException("Vui lòng nhập tên, chọn quận / huyện và nhập địa chỉ chi nhánh mới.");
            String name = r.branchName().strip(), enteredDistrict = r.district().strip().replaceAll("\\s+", " "), address = r.address().strip();
            String district = jdbc.queryForList("SELECT DISTINCT district FROM branches WHERE deleted_at IS NULL ORDER BY district", String.class).stream()
                .filter(value -> districtKey(value).equals(districtKey(enteredDistrict)))
                .findFirst().orElse(enteredDistrict);
            master.validateCourtAddress(district, address);
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM branches WHERE lower(btrim(name))=lower(?) AND district=? AND lower(btrim(address))=lower(?) AND deleted_at IS NULL)", Boolean.class, name, district, address)))
                throw new DuplicateResourceException("Chi nhánh với tên và địa chỉ này đã tồn tại.");
            var branch = new Branch();
            branch.setCode("BR-" + UUID.randomUUID());
            branch.setName(name);branch.setDistrict(district);branch.setAddress(address);
            branchId = branches.saveAndFlush(branch).getId();
        }
        var matches = jdbc.queryForList("SELECT name FROM branches WHERE id=? AND active=true AND deleted_at IS NULL FOR SHARE", branchId);
        if (matches.isEmpty()) throw new BadRequestException("Vui lòng chọn chi nhánh đang hoạt động.");
        String name = (String) matches.get(0).get("name");
        int last = jdbc.queryForObject("SELECT COALESCE(MAX(court_number),0) FROM courts WHERE branch_id=? AND deleted_at IS NULL", Integer.class, branchId);
        List<Court> created = new ArrayList<>();
        for (int i = 1; i <= r.courtCount(); i++) {
            int number = last + i;
            created.add(master.saveCourt(null, new CourtRequest(number, name + " · Sân " + String.format(Locale.ROOT,"%02d",number), r.type(), r.status(), r.basePrice(), null, null, branchId)));
        }
        return created;
    }

    private String districtKey(String value) {
        return value.strip().replaceAll("\\s+", " ").replaceFirst("(?iu)^(quận|huyện)\\s+", "").toLowerCase(Locale.ROOT);
    }
}
