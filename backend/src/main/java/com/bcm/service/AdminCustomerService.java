package com.bcm.service;
import com.bcm.dto.request.AdminCustomerRequest;
import com.bcm.entity.*;
import com.bcm.repository.*;
import com.bcm.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.time.LocalDateTime;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class AdminCustomerService {
 private final AdminAccountService accounts; private final CustomerRepository customers; private final UserRepository users;
 private final com.bcm.security.WalkInGuestIdentity guest; private final RoleRepository roles; private final PasswordEncoder encoder; private final JdbcTemplate jdbc;
 public Map<String,Object> list(String search,int page,String sort) {
  if(page<0) throw new BadRequestException("Invalid page");
  String order="name".equals(sort)?"c.full_name asc,c.id":"c.created_at desc,c.id";
  String where=" from customers c join users u on u.id=c.user_id where u.id<>? and exists(select 1 from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=u.id and r.role_name='CUSTOMER') and not exists(select 1 from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=u.id and r.role_name in ('ADMIN','STAFF')) and (lower(c.full_name) like ? or lower(u.email) like ? or c.phone like ?)";
  String q="%"+search.toLowerCase(Locale.ROOT).trim()+"%";
  var rows=jdbc.queryForList("select c.id,c.full_name as \"fullName\",u.email,c.phone,c.address,c.created_at as \"createdAt\",coalesce(u.deleted_at,c.deleted_at) as \"deletedAt\",(select count(*) from bookings b where b.customer_id=c.id) as \"bookingCount\",(select coalesce(sum(i.total_amount),0) from invoices i where i.customer_id=c.id and i.status='PAID' and i.deleted_at is null) as \"totalSpent\""+where+" order by "+order+" limit 20 offset ?",guest.userId(),q,q,q,(long)page*20);
  long count=jdbc.queryForObject("select count(*)"+where,Long.class,guest.userId(),q,q,q);
  return Map.of("customers",rows,"totalPages",(count+19)/20,"totalElements",count);
 }
 public Map<String,Object> detail(UUID id) {
  var c=customers.findById(id).filter(v->!guest.isGuest(v.getUser().getId()) && v.getUser().hasRole("CUSTOMER") && !v.getUser().hasRole("ADMIN") && !v.getUser().hasRole("STAFF")).orElseThrow(()->new ResourceNotFoundException("Customer not found"));
  var result=new LinkedHashMap<String,Object>();
  result.put("id",c.getId());result.put("userId",c.getUser().getId());
  result.put("fullName",c.getFullName());result.put("email",c.getUser().getEmail());
  result.put("phone",c.getPhone());result.put("address",c.getAddress());result.put("createdAt",c.getCreatedAt());
  result.put("deletedAt",c.getUser().getDeletedAt()!=null?c.getUser().getDeletedAt():c.getDeletedAt());
  result.put("bookings",jdbc.queryForList("select id as \"id\",status as \"status\",court_fee as \"court_fee\",created_at as \"created_at\" from bookings where customer_id=? order by created_at desc,id",id));
  result.put("invoices",jdbc.queryForList("select id as \"id\",status as \"status\",total_amount as \"total_amount\",created_at as \"created_at\" from invoices where customer_id=? and status='PAID' and deleted_at is null order by created_at desc,id",id));
  return result;
 }
 private Customer active(UUID id) {return customers.findById(id).filter(c->c.getDeletedAt()==null && !guest.isGuest(c.getUser().getId())).orElseThrow(()->new ResourceNotFoundException("Customer not found"));}
 @Transactional public UUID save(UUID id,AdminCustomerRequest r) {
  if("0000000000".equals(r.phone().replaceAll("[^0-9]",""))) throw new BadRequestException("Reserved POS guest phone");
  Customer c=id==null?new Customer():active(id); User u=id==null?new User():c.getUser();
  if(id==null && (r.password()==null||r.password().isBlank())) throw new BadRequestException("Password required");
  if(id==null) com.bcm.security.CredentialPolicy.requireNewPassword(r.password());
  String email=com.bcm.security.CredentialPolicy.email(r.email());
  if(jdbc.queryForObject("select count(*) from users where lower(email)=? and id<>?",Long.class,email,u.getId()==null?new UUID(0,0):u.getId())>0) throw new DuplicateResourceException("Email already registered");
  if(id!=null && (u.hasRole("ADMIN")||u.hasRole("STAFF"))) throw new BadRequestException("Managed staff account");
  u.setEmail(email);u.setFullName(r.fullName().trim());u.setPhone(r.phone().trim());u.setAddress(r.address());
  if(id==null) {u.setPasswordHash(encoder.encode(r.password()));u.setRoles(new HashSet<>(Set.of(roles.findByRoleName("CUSTOMER").orElseThrow())));}
  users.saveAndFlush(u);c.setUser(u);c.setFullName(u.getFullName());c.setPhone(u.getPhone());c.setAddress(u.getAddress());return customers.saveAndFlush(c).getId();
 }
 @Transactional public void disable(UUID id) {
  Customer c=customers.findById(id).orElseThrow(()->new ResourceNotFoundException("Customer not found"));
  accounts.disable(c.getUser().getId());
 }
}
