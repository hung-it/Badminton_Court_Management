package com.bcm.service;
import com.bcm.dto.request.AdminAccountUpdateRequest;
import com.bcm.exception.*;
import com.bcm.repository.UserRepository;
import com.bcm.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.time.LocalDateTime;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class AdminAccountService {
 private final JdbcTemplate jdbc; private final UserRepository users; private final com.bcm.security.WalkInGuestIdentity guest;
 private static final String FROM=" from users u left join customers c on c.user_id=u.id left join staffs s on s.user_id=u.id ";
 private static final String SELECT="select u.id,u.full_name,u.email,u.phone,coalesce(c.address,u.address) address,u.created_at,u.deleted_at,c.id customer_id,s.id staff_id,s.position,"
  +"(select string_agg(r.role_name,',' order by case r.role_name when 'ADMIN' then 1 when 'STAFF' then 2 else 3 end) from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=u.id) roles,"
  +"(select count(*) from bookings b where b.customer_id=c.id) booking_count,"
  +"(select coalesce(sum(i.total_amount),0) from invoices i where i.customer_id=c.id and i.status='PAID' and i.deleted_at is null) total_spent";
 private Map<String,Object> row(java.sql.ResultSet rs,int n)throws java.sql.SQLException {
  var v=new LinkedHashMap<String,Object>();
  for(String key:List.of("id","email","phone","address","position"))v.put(key,rs.getObject(key));
  v.put("fullName",rs.getString("full_name"));v.put("createdAt",rs.getObject("created_at",LocalDateTime.class));v.put("deletedAt",rs.getObject("deleted_at",LocalDateTime.class));
  v.put("customerId",rs.getObject("customer_id"));v.put("staffId",rs.getObject("staff_id"));
  String roles=rs.getString("roles");v.put("roles",roles==null?List.of():Arrays.asList(roles.split(",")));
  v.put("bookingCount",rs.getLong("booking_count"));v.put("totalSpent",rs.getBigDecimal("total_spent"));
  boolean active=rs.getObject("deleted_at")==null,protectedGuest=guest.isGuest(rs.getObject("id",UUID.class));
  v.put("canEdit",active&&!protectedGuest);v.put("canLock",active&&!protectedGuest);return v;
 }
 public Map<String,Object> list(String search,String role,String sort,int page){
  if(page<0||!List.of("","ADMIN","STAFF","CUSTOMER").contains(role)||!List.of("created","name").contains(sort))throw new BadRequestException("Invalid account filter");
  String where=" where exists(select 1 from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=u.id and r.role_name in ('ADMIN','STAFF','CUSTOMER')) and (lower(u.full_name) like ? or lower(u.email) like ? or u.phone like ?) and (?='' or exists(select 1 from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=u.id and r.role_name=?))";
  String q="%"+search.strip().toLowerCase(Locale.ROOT)+"%";String order=sort.equals("name")?"u.full_name,u.id":"u.created_at desc,u.id";
  var rows=jdbc.query(SELECT+FROM+where+" order by "+order+" limit 20 offset ?",this::row,q,q,q,role,role,(long)page*20);
  long count=jdbc.queryForObject("select count(*)"+FROM+where,Long.class,q,q,q,role,role);
  return Map.of("accounts",rows,"totalElements",count,"totalPages",(count+19)/20,"page",page);
 }
 public Map<String,Object> detail(UUID id){
  var rows=jdbc.query(SELECT+FROM+" where u.id=?",this::row,id);
  if(rows.isEmpty())throw new ResourceNotFoundException("Account not found");var v=rows.get(0);UUID c=(UUID)v.get("customerId");
  v.put("bookings",c==null?List.of():jdbc.queryForList("select id,status,court_fee,created_at from bookings where customer_id=? order by created_at desc,id",c));
  v.put("invoices",c==null?List.of():jdbc.queryForList("select id,status,total_amount,created_at from invoices where customer_id=? and status='PAID' and deleted_at is null order by created_at desc,id",c));return v;
 }
 private void lockUser(UUID id){if(jdbc.queryForList("select id from users where id=? for update",UUID.class,id).isEmpty())throw new ResourceNotFoundException("Account not found");}
 @Transactional public void update(UUID id,AdminAccountUpdateRequest r){
  if("0000000000".equals(r.phone().strip()))throw new BadRequestException("Reserved POS guest phone");
  lockUser(id);var u=users.findById(id).orElseThrow();
  if(u.getDeletedAt()!=null||guest.isGuest(u.getId()))throw new BadRequestException("Account cannot be edited");
  String email=r.email().strip().toLowerCase(Locale.ROOT);
  if(jdbc.queryForObject("select count(*) from users where lower(email)=? and id<>?",Long.class,email,id)>0)throw new DuplicateResourceException("Email already registered");
  u.setFullName(r.fullName().strip());u.setEmail(email);u.setPhone(r.phone().strip());u.setAddress(r.address());users.saveAndFlush(u);
  jdbc.update("update customers set full_name=?,phone=?,address=?,updated_at=current_timestamp where user_id=?",u.getFullName(),u.getPhone(),u.getAddress(),id);
  jdbc.update("update staffs set full_name=?,phone=?,updated_at=current_timestamp where user_id=?",u.getFullName(),u.getPhone(),id);
 }
 @Transactional public void disable(UUID id){
  var principal=SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  if(!(principal instanceof UserPrincipal p))throw new AccessDeniedException("Admin identity required");UUID actor=UUID.fromString(p.getId());
  // Serialize admin-account locks on an existing role row, including concurrent cross-lock requests.
  jdbc.queryForList("select id from roles where role_name='ADMIN' for update",UUID.class);
  if(jdbc.queryForObject("select count(*) from users u join user_roles ur on ur.user_id=u.id join roles r on r.id=ur.role_id where u.id=? and u.deleted_at is null and r.role_name='ADMIN'",Long.class,actor)==0)throw new AccessDeniedException("Active admin required");
  if(actor.equals(id))throw new BadRequestException("Cannot lock your own account");lockUser(id);var u=users.findById(id).orElseThrow();
  if(guest.isGuest(u.getId()))throw new BadRequestException("POS guest account is protected");if(u.getDeletedAt()!=null)return;
  if(u.hasRole("ADMIN")&&jdbc.queryForObject("select count(distinct u.id) from users u join user_roles ur on ur.user_id=u.id join roles r on r.id=ur.role_id where r.role_name='ADMIN' and u.deleted_at is null",Long.class)<=1)throw new BadRequestException("Cannot lock last active admin");
  u.setDeletedAt(LocalDateTime.now());users.saveAndFlush(u);
  jdbc.update("update customers set deleted_at=?,updated_at=current_timestamp where user_id=?",u.getDeletedAt(),id);
  jdbc.update("update staffs set deleted_at=?,updated_at=current_timestamp where user_id=?",u.getDeletedAt(),id);
 }
}
