package com.bcm.service;
import com.bcm.entity.*;
import com.bcm.dto.request.*;
import com.bcm.repository.*;
import com.bcm.exception.*;
import java.util.*;
import java.math.*;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
@Service @RequiredArgsConstructor @Transactional
public class MasterDataService {
 private final CourtRepository courts;
 private final TimeSlotRepository slots;
 private final CategoryRepository categories;
 private final ProductRepository products;
 private final JdbcTemplate jdbc;
 public List<Court> courts(){return courts.findByDeletedAtIsNullOrderByCourtNumberAsc();}
 public List<TimeSlot> slots(){return slots.findByDeletedAtIsNullOrderByStartTimeAsc();}
 public List<Category> categories(){return categories.findByDeletedAtIsNullOrderByCategoryNameAsc();}
 public List<Product> products(){return products.findByDeletedAtIsNullOrderByNameAsc();}
 public Court court(UUID id){return courts.findByIdAndDeletedAtIsNull(id).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy sân"));}
 public TimeSlot slot(UUID id){return slots.findByIdAndDeletedAtIsNull(id).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy khung giờ"));}
 public Category category(UUID id){return categories.findByIdAndDeletedAtIsNull(id).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy danh mục"));}
 public Product product(UUID id){return products.findByIdAndDeletedAtIsNull(id).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy sản phẩm"));}
 public Court saveCourt(UUID id,CourtRequest r){
  Court c=id==null?new Court():court(id);
  if(courts.existsByCourtNumberAndIdNot(r.courtNumber(),id==null?new UUID(0,0):id))throw new DuplicateResourceException("Số sân đã được sử dụng");
  c.setCourtNumber(r.courtNumber());c.setName(r.name().strip());c.setType(r.type());c.setStatus(r.status());c.setBasePrice(r.basePrice());return courts.saveAndFlush(c);
 }
 private boolean activeCourtBooking(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from booking_details d join bookings b on b.id=d.booking_id where d.court_id=? and b.status in ('PENDING','PAID','CHECKED_IN'))",Boolean.class,id));}
 private boolean slotReferenced(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from booking_details where time_slot_id=?)",Boolean.class,id));}
 public void deleteCourt(UUID id){Court c=court(id);if(activeCourtBooking(id))throw new BadRequestException("Không thể xóa sân có lịch đặt chưa hoàn tất");c.softDelete();courts.saveAndFlush(c);}
 public TimeSlot saveSlot(UUID id,TimeSlotRequest r){
  if(!r.startTime().isBefore(r.endTime()))throw new BadRequestException("Giờ bắt đầu phải trước giờ kết thúc");
  if(r.startTime().getSecond()!=0 || r.endTime().getSecond()!=0 || r.startTime().getNano()!=0 || r.endTime().getNano()!=0)throw new BadRequestException("Khung giờ phải chính xác đến phút");
  if(slots.overlaps(id==null?new UUID(0,0):id,r.startTime(),r.endTime()))throw new DuplicateResourceException("Khung giờ bị chồng lấn");
  TimeSlot s=id==null?new TimeSlot():slot(id);
  if(id!=null && (!s.getStartTime().equals(r.startTime()) || !s.getEndTime().equals(r.endTime())) && slotReferenced(id))throw new BadRequestException("Khung giờ đã được đặt; hãy tạo khung giờ mới để giữ lịch sử");
  BigDecimal multiplier=r.priceMultiplier();
  if(multiplier==null){
   boolean peak=r.startTime().compareTo(LocalTime.of(17,0))>=0 && r.endTime().compareTo(LocalTime.of(21,0))<=0;
   boolean crosses=r.startTime().isBefore(LocalTime.of(17,0))&&r.endTime().isAfter(LocalTime.of(17,0)) || r.startTime().isBefore(LocalTime.of(21,0))&&r.endTime().isAfter(LocalTime.of(21,0));
   if(crosses)throw new BadRequestException("Hãy chia khung giờ tại 17:00 hoặc 21:00, hoặc nhập hệ số riêng");
   multiplier=new BigDecimal(peak?"1.50":"1.00");
  }
  s.setStartTime(r.startTime());s.setEndTime(r.endTime());s.setPriceMultiplier(multiplier);return slots.saveAndFlush(s);
 }
 public void deleteSlot(UUID id){TimeSlot s=slot(id);if(slotReferenced(id))throw new BadRequestException("Không thể xóa khung giờ đã có lịch đặt");s.softDelete();slots.saveAndFlush(s);}
 public Category saveCategory(UUID id,CategoryRequest r){
  if(categories.existsByCategoryNameIgnoreCaseAndDeletedAtIsNullAndIdNot(r.categoryName().strip(),id==null?new UUID(0,0):id))throw new DuplicateResourceException("Tên danh mục đã tồn tại");
  Category c=id==null?new Category():category(id);c.setCategoryName(r.categoryName().strip());return categories.saveAndFlush(c);
 }
 public void deleteCategory(UUID id){Category c=category(id);if(products.existsByCategoryIdAndDeletedAtIsNull(id))throw new BadRequestException("Danh mục còn sản phẩm; hãy chuyển hoặc xóa sản phẩm trước");c.softDelete();categories.saveAndFlush(c);}
 public Product saveProduct(UUID id,ProductRequest r){
  category(r.categoryId());
  if(r.type()==ProductType.SERVICE && r.stockQuantity()!=0)throw new BadRequestException("Dịch vụ phải có tồn kho bằng 0");
  Product p=id==null?new Product():product(id);
  if(id!=null){
   if(r.version()==null || !r.version().equals(p.getVersion()))throw new DuplicateResourceException("Dữ liệu đã thay đổi; hãy tải lại sản phẩm");
   if(!r.stockQuantity().equals(p.getStockQuantity()))throw new BadRequestException("Chỉ nhập tồn ban đầu khi tạo; điều chỉnh tồn qua phân hệ kho");
  }
  p.setCategoryId(r.categoryId());p.setName(r.name().strip());p.setType(r.type());p.setUnit(r.unit().strip());p.setPrice(r.price());p.setStockQuantity(r.stockQuantity());p.setImageUrl(r.imageUrl());return products.saveAndFlush(p);
 }
 public void deleteProduct(UUID id){Product p=product(id);p.softDelete();products.saveAndFlush(p);}
 public Map<String,Object> quote(UUID courtId,UUID slotId){
  Court c=court(courtId);TimeSlot s=slot(slotId);
  if(c.getStatus()!=CourtStatus.AVAILABLE)throw new BadRequestException("Sân không hoạt động");
  return Map.of("courtId",courtId,"timeSlotId",slotId,"basePrice",c.getBasePrice(),"priceMultiplier",s.getPriceMultiplier(),"price",c.getBasePrice().multiply(s.getPriceMultiplier()).setScale(2,RoundingMode.HALF_UP));
 }
}
