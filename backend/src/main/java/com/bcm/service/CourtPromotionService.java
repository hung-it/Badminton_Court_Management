package com.bcm.service;
import com.bcm.dto.request.CourtPromotionRequest;
import com.bcm.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
@Transactional(readOnly=true)
public class CourtPromotionService {
 private final JdbcTemplate jdbc;
 public List<Map<String,Object>> list() {
  return jdbc.queryForList("SELECT d.id,p.name,p.code,d.rule_type,d.discount_type,d.discount_value,COALESCE(d.min_invoice_amount,0) min_invoice_amount,d.max_discount_amount,d.max_total_usage,d.max_usage_per_customer,p.valid_from,p.valid_to,p.is_active FROM discount_rules d JOIN promotions p ON p.id=d.promotion_id WHERE d.rule_type IN ('INVOICE_TOTAL','VOUCHER') AND p.deleted_at IS NULL ORDER BY d.rule_type,p.code");
 }
 @Transactional public void save(UUID id,CourtPromotionRequest v) {
  String code=v.code().toUpperCase(Locale.ROOT);
  if (v.validTo()!=null && v.validTo().isBefore(v.validFrom())) throw new BadRequestException("Ngày kết thúc không hợp lệ");
  if (v.discountType().equals("PERCENT") && v.discountValue().compareTo(new BigDecimal("100"))>0) throw new BadRequestException("Tỷ lệ không vượt 100%");
  UUID pid;
  if(id==null) {pid=UUID.randomUUID(); if(Set.of("NEWMEMBER","KMKH10","INVOICE_TOTAL").contains(code)) throw new BadRequestException("Mã dành riêng cho hệ thống");}
  else {
   var old=managed(id); pid=(UUID)old.get("promotion_id");
   if(!old.get("code").equals(code) || !old.get("rule_type").equals(v.ruleType())) throw new BadRequestException("Không đổi mã hoặc loại chương trình; hãy tạo chương trình mới");
   if(Set.of("NEWMEMBER","KMKH10").contains(code) && (!v.discountType().equals("PERCENT") || (code.equals("NEWMEMBER") && !Integer.valueOf(1).equals(v.maxUsagePerCustomer())))) throw new BadRequestException("Voucher đặc biệt phải giảm theo %; NEWMEMBER tối đa một lần mỗi khách");
  }
  if(jdbc.queryForObject("SELECT count(*) FROM promotions WHERE upper(code)=? AND id<>?",Long.class,code,pid)>0) throw new DuplicateResourceException("Mã khuyến mãi đã tồn tại");
  if(id==null) jdbc.update("INSERT INTO promotions(id,code,name,valid_from,valid_to,is_active) VALUES (?,?,?,?,?,?)",pid,code,v.name().strip(),v.validFrom(),v.validTo(),v.active());
  else jdbc.update("UPDATE promotions SET name=?,valid_from=?,valid_to=?,is_active=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",v.name().strip(),v.validFrom(),v.validTo(),v.active(),pid);
  if(id==null) jdbc.update("INSERT INTO discount_rules(id,promotion_id,rule_type,voucher_code,discount_type,discount_value,min_invoice_amount,max_discount_amount,max_total_usage,max_usage_per_customer,min_quantity) VALUES (?,?,?,?,?,?,?,?,?,?,1)",UUID.randomUUID(),pid,v.ruleType(),v.ruleType().equals("VOUCHER")?code:null,v.discountType(),v.discountValue(),v.minInvoiceAmount(),v.maxDiscountAmount(),v.maxTotalUsage(),v.maxUsagePerCustomer());
  else jdbc.update("UPDATE discount_rules SET discount_type=?,discount_value=?,min_invoice_amount=?,max_discount_amount=?,max_total_usage=?,max_usage_per_customer=? WHERE id=?",v.discountType(),v.discountValue(),v.minInvoiceAmount(),v.maxDiscountAmount(),v.maxTotalUsage(),v.maxUsagePerCustomer(),id);
 }
 private Map<String,Object> managed(UUID id) {
  var rows=jdbc.queryForList("SELECT d.*,p.code FROM discount_rules d JOIN promotions p ON p.id=d.promotion_id WHERE d.id=? AND d.rule_type IN ('INVOICE_TOTAL','VOUCHER') AND p.deleted_at IS NULL FOR UPDATE OF p",id);
  if(rows.isEmpty()) throw new ResourceNotFoundException("Không tìm thấy chương trình"); return rows.get(0);
 }
 @Transactional public void remove(UUID id) {var p=managed(id); jdbc.update("UPDATE promotions SET is_active=false,deleted_at=CURRENT_TIMESTAMP WHERE id=?",p.get("promotion_id"));}
 public BigDecimal automatic(BigDecimal subtotal,LocalDate date) {
  BigDecimal best=BigDecimal.ZERO.setScale(2);
  for(var rule:jdbc.queryForList("SELECT d.* FROM discount_rules d JOIN promotions p ON p.id=d.promotion_id WHERE d.rule_type='INVOICE_TOTAL' AND p.is_active AND p.deleted_at IS NULL AND p.valid_from<=? AND (p.valid_to IS NULL OR p.valid_to>=?) ORDER BY p.code",date,date)) best=best.max(amount(rule,subtotal));
  return best;
 }
 public BigDecimal amount(Map<String,Object> r,BigDecimal subtotal) {
  BigDecimal min=(BigDecimal)r.get("min_invoice_amount"); if(min!=null && subtotal.compareTo(min)<0) return BigDecimal.ZERO.setScale(2);
  BigDecimal value=(BigDecimal)r.get("discount_value");
  BigDecimal amount="PERCENT".equals(r.get("discount_type"))?subtotal.multiply(value).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP):value;
  if(r.get("max_discount_amount")!=null) amount=amount.min((BigDecimal)r.get("max_discount_amount"));
  return amount.max(BigDecimal.ZERO).min(subtotal).setScale(2,RoundingMode.HALF_UP);
 }
 // Lock the campaign row before counting reservations, including reservations in other transactions.
 public Map<String,Object> voucher(String code,LocalDate date) {
  var rows=jdbc.queryForList("SELECT d.* FROM promotions p JOIN discount_rules d ON d.promotion_id=p.id WHERE p.code=? AND d.voucher_code=? AND d.rule_type='VOUCHER' AND p.is_active AND p.deleted_at IS NULL AND p.valid_from<=? AND (p.valid_to IS NULL OR p.valid_to>=?) FOR UPDATE OF p",code,code,date,date);
  return rows.size()==1?rows.get(0):null;
 }
 public String limits(Map<String,Object> rule,UUID owner,UUID booking,String code,LocalDateTime now) {
  String where=" FROM bookings b WHERE b.id<>? AND b.promotion_code=? AND ((b.status='PENDING' AND b.expires_at>? AND (NOT EXISTS (SELECT 1 FROM payment_transactions t WHERE t.booking_id=b.id) OR EXISTS (SELECT 1 FROM payment_transactions t WHERE t.booking_id=b.id AND t.status='PENDING'))) OR (b.status IN ('PAID','CHECKED_IN','COMPLETED','NO_SHOW') AND EXISTS (SELECT 1 FROM payment_transactions t WHERE t.booking_id=b.id AND t.status IN ('SUCCESS','PENDING'))))";
  long all=jdbc.queryForObject("SELECT count(*)"+where,Long.class,booking,code,now);
  long own=jdbc.queryForObject("SELECT count(*)"+where+" AND b.customer_id=?",Long.class,booking,code,now,owner);
  long pos=jdbc.queryForObject("SELECT count(*) FROM customer_voucher_usage u JOIN invoices i ON i.id=u.invoice_id WHERE u.discount_rule_id=? AND i.status='PAID'",Long.class,rule.get("id"));
  long ownPos=jdbc.queryForObject("SELECT count(*) FROM customer_voucher_usage u JOIN invoices i ON i.id=u.invoice_id WHERE u.discount_rule_id=? AND u.customer_id=? AND i.status='PAID'",Long.class,rule.get("id"),owner);
  if(rule.get("max_total_usage")!=null && all+pos>=((Number)rule.get("max_total_usage")).longValue()) return "Voucher đã hết lượt sử dụng";
  if(rule.get("max_usage_per_customer")!=null && own+ownPos>=((Number)rule.get("max_usage_per_customer")).longValue()) return "Khách hàng đã dùng hết lượt voucher";
  return null;
 }
}
