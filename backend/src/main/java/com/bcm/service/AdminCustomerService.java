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
 private final CustomerRepository customers; private final UserRepository users;
 private final com.bcm.security.WalkInGuestIdentity guest; private final RoleRepository roles; private final PasswordEncoder encoder; private final JdbcTemplate jdbc;
 public Map<String,Object> list(String search,int page,String sort) {
  if(page<0) throw new BadRequestException("Invalid page");
  String order="name".equals(sort)?"c.full_name asc,c.id":"c.created_at desc,c.id";
  String where=" from customers c join users u on u.id=c.user_id where c.deleted_at is null and u.id<>? and (lower(c.full_name) like ? or lower(u.email) like ? or c.phone like ?)";
  String q="%"+search.toLowerCase(Locale.ROOT).trim()+"%";
  var rows=jdbc.queryForList("select c.id,c.full_name as \"fullName\",u.email,c.phone,c.address,c.created_at as \"createdAt\",(select count(*) from bookings b where b.customer_id=c.id) as \"bookingCount\",(select coalesce(sum(i.total_amount),0) from invoices i where i.customer_id=c.id and i.status='PAID' and i.deleted_at is null) as \"totalSpent\""+where+" order by "+order+" limit 20 offset ?",guest.userId(),q,q,q,(long)page*20);
  long count=jdbc.queryForObject("select count(*)"+where,Long.class,guest.userId(),q,q,q);
  return Map.of("customers",rows,"totalPages",(count+19)/20,"totalElements",count);
 }
 public Map<String,Object> detail(UUID id) {
  var c=active(id);
  return Map.of("id",c.getId(),"bookings",jdbc.queryForList("select id,status,court_fee,created_at from bookings where customer_id=? order by created_at desc",id),"invoices",jdbc.queryForList("select id,status,total_amount,created_at from invoices where customer_id=? and status='PAID' and deleted_at is null order by created_at desc",id));
 }
 private Customer active(UUID id) {return customers.findById(id).filter(c->c.getDeletedAt()==null && !guest.isGuest(c.getUser().getId())).orElseThrow(()->new ResourceNotFoundException("Customer not found"));}
 @Transactional public UUID save(UUID id,AdminCustomerRequest r) {
  if("0000000000".equals(r.phone().trim())) throw new BadRequestException("Reserved POS guest phone");
  Customer c=id==null?new Customer():active(id); User u=id==null?new User():c.getUser();
  if(id==null && (r.password()==null||r.password().isBlank())) throw new BadRequestException("Password required");
  String email=r.email().trim().toLowerCase(Locale.ROOT);
  if(jdbc.queryForObject("select count(*) from users where lower(email)=? and id<>?",Long.class,email,u.getId()==null?new UUID(0,0):u.getId())>0) throw new DuplicateResourceException("Email already registered");
  if(id!=null && (u.hasRole("ADMIN")||u.hasRole("STAFF"))) throw new BadRequestException("Managed staff account");
  u.setEmail(email);u.setFullName(r.fullName().trim());u.setPhone(r.phone().trim());u.setAddress(r.address());
  if(id==null) {u.setPasswordHash(encoder.encode(r.password()));u.setRoles(new HashSet<>(Set.of(roles.findByRoleName("CUSTOMER").orElseThrow())));}
  users.saveAndFlush(u);c.setUser(u);c.setFullName(u.getFullName());c.setPhone(u.getPhone());c.setAddress(u.getAddress());return customers.saveAndFlush(c).getId();
 }
 @Transactional public void disable(UUID id) {
  Customer c=active(id);User u=c.getUser();
  if(u.hasRole("ADMIN")||u.hasRole("STAFF")) throw new BadRequestException("Managed staff account");
  c.setDeletedAt(LocalDateTime.now());u.setDeletedAt(c.getDeletedAt());customers.flush();
 }
}
