package com.bcm.service;

import com.bcm.dto.response.PosDraftPage;
import com.bcm.exception.BadRequestException;
import com.bcm.security.CurrentStaffService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PosDraftService {
    private final JdbcTemplate jdbc;
    private final CurrentStaffService staff;

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PosDraftPage list(UUID bookingId, String search, int page, int size) {
        staff.requireUserId();
        if(page<0||size<1||size>100||search.length()>255) throw new BadRequestException("Invalid draft filter");
        String customer="coalesce(nullif(btrim(b.contact_full_name),''),c.full_name,'Khách vãng lai')";
        String from=" FROM invoices i LEFT JOIN bookings b ON b.id=i.booking_id LEFT JOIN customers c ON c.id=i.customer_id LEFT JOIN users u ON u.id=i.created_by_user";
        String where=" WHERE i.status='DRAFT' AND i.deleted_at IS NULL AND i.parent_invoice_id IS NULL";
        var args=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
        if(bookingId!=null) {where+=" AND i.booking_id=:booking";args.addValue("booking",bookingId);}
        if(!search.isBlank()) {
            where+=" AND (lower(i.id::text) LIKE :search OR lower(i.booking_id::text) LIKE :search OR lower("+customer+") LIKE :search)";
            args.addValue("search","%"+search.strip().toLowerCase(Locale.ROOT)+"%");
        }
        var query=new NamedParameterJdbcTemplate(jdbc);
        long count=query.queryForObject("SELECT count(*)"+from+where,args,Long.class);
        args.addValue("limit",size).addValue("offset",(long)page*size);
        var headers=query.query("SELECT i.id,i.booking_id,"+customer+" AS customer_name,i.created_by_user,u.full_name AS actor_name,i.created_at,i.total_amount"+from+where+" ORDER BY i.created_at DESC,i.id DESC LIMIT :limit OFFSET :offset",args,
            (rs,n)->new PosDraftPage.Draft(rs.getObject("id",UUID.class),rs.getObject("booking_id",UUID.class),rs.getString("customer_name"),rs.getObject("created_by_user",UUID.class),rs.getString("actor_name"),rs.getObject("created_at",java.time.LocalDateTime.class),rs.getBigDecimal("total_amount"),List.of()));
        Map<UUID,List<PosDraftPage.Item>> items=new HashMap<>();
        if(!headers.isEmpty()) query.query("SELECT d.invoice_id,d.product_id,coalesce(d.product_name,p.name) AS product_name,d.quantity,d.unit_price,d.discount_amount FROM invoice_details d LEFT JOIN products p ON p.id=d.product_id WHERE d.invoice_id IN (:ids) ORDER BY d.created_at,d.id",
            Map.of("ids",headers.stream().map(PosDraftPage.Draft::id).toList()),(org.springframework.jdbc.core.RowCallbackHandler)rs->{
                var price=rs.getBigDecimal("unit_price");var discount=rs.getBigDecimal("discount_amount");int quantity=rs.getInt("quantity");
                items.computeIfAbsent(rs.getObject("invoice_id",UUID.class),id->new ArrayList<>()).add(new PosDraftPage.Item(rs.getObject("product_id",UUID.class),rs.getString("product_name"),quantity,price,discount,price.multiply(java.math.BigDecimal.valueOf(quantity)).subtract(discount)));
            });
        var content=headers.stream().map(h->new PosDraftPage.Draft(h.id(),h.bookingId(),h.customerName(),h.createdByUser(),h.createdByName(),h.createdAt(),h.totalAmount(),items.getOrDefault(h.id(),List.of()))).toList();
        return new PosDraftPage(content,page,size,count,(count+size-1)/size);
    }
}
