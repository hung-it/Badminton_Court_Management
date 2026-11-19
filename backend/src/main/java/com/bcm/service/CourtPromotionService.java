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
 // A payment can still settle after expires_at. Release its voucher only after a terminal
 // payment state or the atomic booking expiration, never on a wall-clock pre-check alone.
 static final String PENDING_RESERVATION = "((b.expires_at>? AND NOT EXISTS (SELECT 1 FROM payment_transactions t WHERE t.booking_id=b.id)) OR EXISTS (SELECT 1 FROM payment_transactions t WHERE t.booking_id=b.id AND t.status='PENDING'))";
 // Each draft keeps its own usage row after merging, so N discounts remain N uses.
 static final String POS_USAGE_RESERVATION = "(i.status IN ('DRAFT','PAID') OR (i.status='CANCELLED' AND EXISTS (SELECT 1 FROM invoices parent WHERE parent.id=i.parent_invoice_id AND parent.status='PAID')))";
 private final JdbcTemplate jdbc;
 public List<Map<String,Object>> list() {
  return jdbc.queryForList("SELECT d.id,p.name,p.code,d.rule_type,d.discount_type,d.discount_value,COALESCE(d.min_invoice_amount,0) min_invoice_amount,d.max_discount_amount,d.max_total_usage,d.max_usage_per_customer,p.valid_from,p.valid_to,p.is_active FROM discount_rules d JOIN promotions p ON p.id=d.promotion_id WHERE d.rule_type IN ('INVOICE_TOTAL','VOUCHER') AND d.deleted_at IS NULL AND d.is_active AND p.deleted_at IS NULL ORDER BY d.rule_type,p.code");
 }
 @Transactional public void save(UUID id,CourtPromotionRequest v) {
  String code=v.code().toUpperCase(Locale.ROOT);
  if (v.validTo()!=null && v.validTo().isBefore(v.validFrom())) throw new BadRequestException("Ngày kết thúc không hợp lệ");
  if (v.discountType().equals("PERCENT") && v.discountValue().compareTo(new BigDecimal("100"))>=0) throw new BadRequestException("Ưu đãi phải giữ tiền sân phải thanh toán lớn hơn 0; tỷ lệ phải dưới 100%");
  BigDecimal effective=v.maxDiscountAmount()==null?v.discountValue():v.discountValue().min(v.maxDiscountAmount());
  if(v.discountType().equals("AMOUNT") && (v.minInvoiceAmount()==null || effective.compareTo(v.minInvoiceAmount())>=0))
      throw new BadRequestException("Giảm tiền cố định phải nhỏ hơn mức tiền sân tối thiểu áp dụng.");
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
  var rows=jdbc.queryForList("SELECT d.*,p.code FROM discount_rules d JOIN promotions p ON p.id=d.promotion_id WHERE d.id=? AND d.rule_type IN ('INVOICE_TOTAL','VOUCHER') AND d.deleted_at IS NULL AND d.is_active AND p.deleted_at IS NULL FOR UPDATE OF p",id);
  if(rows.isEmpty()) throw new ResourceNotFoundException("Không tìm thấy chương trình"); return rows.get(0);
 }
 @Transactional public void remove(UUID id) {managed(id); jdbc.update("UPDATE discount_rules SET is_active=false,deleted_at=CURRENT_TIMESTAMP WHERE id=?",id);}

 public List<Map<String,Object>> programs(boolean productsOnly) {
  var programs=jdbc.queryForList("SELECT p.id,p.code,p.name,p.description,p.valid_from,p.valid_to,p.is_active FROM promotions p WHERE p.deleted_at IS NULL "
   +(productsOnly?"AND EXISTS(SELECT 1 FROM discount_rules d WHERE d.promotion_id=p.id AND d.rule_type='PRODUCT' AND d.deleted_at IS NULL) ":"")+"ORDER BY p.created_at DESC,p.id");
  var all=jdbc.queryForList("SELECT d.*,pr.name product_name FROM discount_rules d JOIN promotions p ON p.id=d.promotion_id LEFT JOIN products pr ON pr.id=d.target_product_id WHERE p.deleted_at IS NULL AND d.deleted_at IS NULL "+(productsOnly?"AND d.rule_type='PRODUCT' ":"")+"ORDER BY d.created_at,d.id");
  for(var p:programs)p.put("rules",all.stream().filter(r->p.get("id").equals(r.get("promotion_id"))).toList());
  return programs;
 }
 private void lockProgram(UUID id) {
  if(jdbc.queryForList("SELECT id FROM promotions WHERE id=? AND deleted_at IS NULL FOR UPDATE",id).isEmpty())throw new ResourceNotFoundException("Không tìm thấy chương trình");
 }
 @Transactional public UUID saveProgram(UUID id,CourtPromotionRequest.Program v) {
  if(v.validTo()!=null&&v.validTo().isBefore(v.validFrom()))throw new BadRequestException("Ngày kết thúc không hợp lệ");
  boolean creating=id==null;
  if(creating){if(v.rules().isEmpty())throw new BadRequestException("Thêm ít nhất một quy tắc");id=UUID.randomUUID();}
  else lockProgram(id);
  if(!creating&&v.rules().stream().anyMatch(r->r.id()==null&&!r.removed()&&r.ruleType().equals("VOUCHER"))&&jdbc.queryForObject("SELECT count(*) FROM invoices WHERE promotion_id=? AND status='DRAFT' AND applied_discount_rule_id IS NULL",Long.class,id)>0)
   throw new DuplicateResourceException("Hoàn tất hóa đơn nháp cũ trước khi bổ sung voucher cho chương trình");
  if(creating)jdbc.update("INSERT INTO promotions(id,code,name,description,valid_from,valid_to,is_active) VALUES (?,?,?,?,?,?,?)",id,"PROGRAM-"+id,v.name().strip(),v.description(),v.validFrom(),v.validTo(),v.active());
  else jdbc.update("UPDATE promotions SET name=?,description=?,valid_from=?,valid_to=?,is_active=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",v.name().strip(),v.description(),v.validFrom(),v.validTo(),v.active(),id);
  Set<UUID> seen=new HashSet<>();Set<String> codes=new HashSet<>();
  for(var r:v.rules()) {
   UUID rid=r.id()==null?UUID.randomUUID():r.id();
   if(!seen.add(rid))throw new BadRequestException("Quy tắc bị lặp");
   Map<String,Object> old=null;
   if(r.id()!=null){var found=jdbc.queryForList("SELECT * FROM discount_rules WHERE id=? AND promotion_id=? AND deleted_at IS NULL",rid,id);if(found.isEmpty())throw new BadRequestException("Quy tắc không thuộc chương trình");old=found.get(0);if(!old.get("rule_type").equals(r.ruleType()))throw new BadRequestException("Không đổi loại quy tắc đã lưu");}
   if(r.removed()){if(old!=null)jdbc.update("UPDATE discount_rules SET is_active=false,deleted_at=CURRENT_TIMESTAMP WHERE id=?",rid);continue;}
   String voucher=r.voucherCode()==null?null:r.voucherCode().strip().toUpperCase(Locale.ROOT);
   if(r.ruleType().equals("PRODUCT")) {
    if(r.productId()==null||r.minQuantity()==null||r.minQuantity()<1)throw new BadRequestException("Chọn sản phẩm và số lượng tối thiểu");
    if(!r.discountType().equals("PERCENT"))throw new BadRequestException("Quy tắc sản phẩm hiện hỗ trợ giảm phần trăm");
    if(jdbc.queryForObject("SELECT count(*) FROM products WHERE id=? AND deleted_at IS NULL",Long.class,r.productId())==0) {
     if(old==null||!r.productId().equals(old.get("target_product_id")))throw new ResourceNotFoundException("Không tìm thấy sản phẩm");
    }
    if(v.validTo()==null)throw new BadRequestException("Chương trình sản phẩm cần ngày kết thúc");
   } else {
    if(r.discountType().equals("AMOUNT")&&(r.minInvoiceAmount()==null||r.discountValue().min(r.maxDiscountAmount()==null?r.discountValue():r.maxDiscountAmount()).compareTo(r.minInvoiceAmount())>=0))throw new BadRequestException("Mức giảm cố định phải nhỏ hơn giá trị tối thiểu");
   }
   if(r.discountType().equals("PERCENT")&&r.discountValue().compareTo(new BigDecimal(r.ruleType().equals("PRODUCT")?"100":"99.99"))>0)throw new BadRequestException("Tỷ lệ giảm không hợp lệ");
   if(r.ruleType().equals("VOUCHER")) {
    if(voucher==null||!voucher.matches("[A-Z0-9_-]{2,50}"))throw new BadRequestException("Mã voucher không hợp lệ");
    if(old!=null&&!Objects.equals(voucher,old.get("voucher_code")))throw new BadRequestException("Không đổi mã voucher đã lưu");
    if(Set.of("NEWMEMBER","KMKH10","INVOICE_TOTAL").contains(voucher)) {
     if(old==null||!r.discountType().equals("PERCENT")||(voucher.equals("NEWMEMBER")&&!Integer.valueOf(1).equals(r.maxUsagePerCustomer())))throw new BadRequestException("Giữ nguyên điều kiện voucher hệ thống");
    }
    if(!codes.add(voucher)||jdbc.queryForObject("SELECT count(*) FROM discount_rules WHERE upper(voucher_code)=? AND id<>?",Long.class,voucher,rid)>0)throw new DuplicateResourceException("Mã voucher đã tồn tại");
   } else voucher=null;
   if(r.id()==null)jdbc.update("INSERT INTO discount_rules(id,promotion_id,rule_type,discount_type,discount_value,min_quantity,is_active,target_product_id,voucher_code) VALUES (?,?,?,?,?,1,?,?,?)",rid,id,r.ruleType(),r.discountType(),r.discountValue(),r.active(),r.ruleType().equals("PRODUCT")?r.productId():null,voucher);
   // PRODUCT/VOUCHER CHECK constraints require their target/code in the same INSERT.
   jdbc.update("UPDATE discount_rules SET target_product_id=?,min_quantity=?,discount_type=?,discount_value=?,min_invoice_amount=?,max_discount_amount=?,max_total_usage=?,max_usage_per_customer=?,voucher_code=?,is_active=? WHERE id=?",r.ruleType().equals("PRODUCT")?r.productId():null,r.ruleType().equals("PRODUCT")?r.minQuantity():1,r.discountType(),r.discountValue(),r.minInvoiceAmount()==null?BigDecimal.ZERO:r.minInvoiceAmount(),r.maxDiscountAmount(),r.ruleType().equals("VOUCHER")?r.maxTotalUsage():null,r.ruleType().equals("VOUCHER")?r.maxUsagePerCustomer():null,voucher,r.active(),rid);
  }
  return id;
 }
 @Transactional public void programState(UUID id,boolean active,boolean remove) {
  lockProgram(id);jdbc.update("UPDATE promotions SET is_active=?,deleted_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE deleted_at END,updated_at=CURRENT_TIMESTAMP WHERE id=?",active,remove,id);
 }
 @Transactional public void ruleState(UUID id,UUID rule,boolean active,boolean remove) {
  lockProgram(id);
  if(jdbc.update("UPDATE discount_rules SET is_active=?,deleted_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE deleted_at END WHERE id=? AND promotion_id=? AND deleted_at IS NULL",active,remove,rule,id)==0)throw new ResourceNotFoundException("Không tìm thấy quy tắc");
 }
 public BigDecimal automatic(BigDecimal subtotal,LocalDate date) {
  BigDecimal best=BigDecimal.ZERO.setScale(2);
  for(var rule:jdbc.queryForList("SELECT d.* FROM discount_rules d JOIN promotions p ON p.id=d.promotion_id WHERE d.rule_type='INVOICE_TOTAL' AND p.is_active AND d.deleted_at IS NULL AND d.is_active AND p.deleted_at IS NULL AND p.valid_from<=? AND (p.valid_to IS NULL OR p.valid_to>=?) ORDER BY p.code",date,date)) best=best.max(amount(rule,subtotal));
  return best;
 }
 public Map<String,Object> bestAutomaticRule(BigDecimal subtotal, LocalDate date) {
  if (subtotal == null || subtotal.signum() <= 0) return null;
  Map<String,Object> bestRule = null;
  BigDecimal best = BigDecimal.ZERO.setScale(2);
  for(var rule:jdbc.queryForList("SELECT d.*, p.name as promotion_name, p.code as promotion_code FROM discount_rules d JOIN promotions p ON p.id=d.promotion_id WHERE d.rule_type='INVOICE_TOTAL' AND p.is_active AND d.deleted_at IS NULL AND d.is_active AND p.deleted_at IS NULL AND p.valid_from<=? AND (p.valid_to IS NULL OR p.valid_to>=?) ORDER BY p.code",date,date)) {
      BigDecimal min = (BigDecimal) rule.get("min_invoice_amount");
      if (min != null && subtotal.compareTo(min) < 0) continue;
      BigDecimal amt = amount(rule, subtotal);
      if (amt.compareTo(best) > 0) {
          best = amt;
          bestRule = rule;
      }
  }
  return bestRule;
 }
 public BigDecimal amount(Map<String,Object> r,BigDecimal subtotal) {
  BigDecimal min=(BigDecimal)r.get("min_invoice_amount"); if(min!=null && subtotal.compareTo(min)<0) return BigDecimal.ZERO.setScale(2);
  BigDecimal value=(BigDecimal)r.get("discount_value");
  BigDecimal amount="PERCENT".equals(r.get("discount_type"))?subtotal.multiply(value).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP):value;
  if(r.get("max_discount_amount")!=null) amount=amount.min((BigDecimal)r.get("max_discount_amount"));
  amount=amount.max(BigDecimal.ZERO).min(subtotal).setScale(2,RoundingMode.HALF_UP);
  if(subtotal.subtract(amount).signum()<=0) throw new BadRequestException("Ưu đãi không hợp lệ: tiền sân sau giảm phải lớn hơn 0.");
  return amount;
 }
 // Lock the campaign row before counting reservations, including reservations in other transactions.
 public Map<String,Object> voucher(String code,LocalDate date) {
  var rows=jdbc.queryForList("SELECT d.*, p.name as promotion_name, p.code as promotion_code FROM promotions p JOIN discount_rules d ON d.promotion_id=p.id WHERE upper(d.voucher_code)=? AND d.rule_type='VOUCHER' AND p.is_active AND d.deleted_at IS NULL AND d.is_active AND p.deleted_at IS NULL AND p.valid_from<=? AND (p.valid_to IS NULL OR p.valid_to>=?) FOR UPDATE OF p",code,date,date);
  return rows.size()==1?rows.get(0):null;
 }
 public String limits(Map<String,Object> rule,UUID owner,UUID booking,String code,LocalDateTime now) {
  String where=" FROM bookings b WHERE b.id IS DISTINCT FROM ? AND b.promotion_code=? AND ((b.status='PENDING' AND "+PENDING_RESERVATION+") OR (b.status IN ('PAID','COMPLETED') AND EXISTS (SELECT 1 FROM payment_transactions t WHERE t.booking_id=b.id AND t.status IN ('SUCCESS','PENDING'))))";
  long all=jdbc.queryForObject("SELECT count(*)"+where,Long.class,booking,code,now);
  long own=jdbc.queryForObject("SELECT count(*)"+where+" AND b.customer_id=?",Long.class,booking,code,now,owner);
  long pos=jdbc.queryForObject("SELECT count(*) FROM customer_voucher_usage u JOIN invoices i ON i.id=u.invoice_id WHERE u.discount_rule_id=? AND "+POS_USAGE_RESERVATION,Long.class,rule.get("id"));
  long ownPos=jdbc.queryForObject("SELECT count(*) FROM customer_voucher_usage u JOIN invoices i ON i.id=u.invoice_id WHERE u.discount_rule_id=? AND u.customer_id=? AND "+POS_USAGE_RESERVATION,Long.class,rule.get("id"),owner);
  if(rule.get("max_total_usage")!=null && all+pos>=((Number)rule.get("max_total_usage")).longValue()) return "Voucher đã hết lượt sử dụng";
  if(rule.get("max_usage_per_customer")!=null && own+ownPos>=((Number)rule.get("max_usage_per_customer")).longValue()) return "Khách hàng đã dùng hết lượt voucher";
  return null;
 }
}
