# Các Điểm Lưu Ý Kỹ Thuật & Giải Pháp

## 1. Optimistic Locking - Áp Dụng Đúng Chỗ

### ❌ Hiểu Nhầm Phổ Biến
README viết: *"Kiểm soát chống âm kho (Optimistic Locking)"* - **ĐÃ ĐÚNG**

Nhưng nếu hiểu nhầm rằng **Optimistic Locking dùng cho chống trùng lịch đặt sân** → **SAI HOÀN TOÀN**

---

### ✅ Phân Biệt 2 Vấn Đề Khác Nhau

| Vấn đề | Race Condition | Giải pháp | Lý do |
|--------|----------------|-----------|-------|
| **Chống âm kho (Products)** | 2 thu ngân cùng bán sản phẩm cuối cùng | **Optimistic Locking** (`version` column + JPA `@Version`) | - Conflict ít xảy ra<br/>- Retry được (bán sản phẩm khác)<br/>- Performance cao (không lock DB) |
| **Chống trùng lịch (Bookings)** | 2 khách cùng đặt 1 sân/1 giờ | **DB Constraint + Pessimistic Lock** | - Conflict phải fail fast<br/>- KHÔNG retry được (sân đã mất)<br/>- User experience quan trọng |

---

### 🔧 Giải Pháp Cụ Thể

#### 1.1. Chống Âm Kho (Products) - Optimistic Locking

```java
// Entity
@Entity
@Table(name = "products")
public class Product {
    @Id
    private UUID id;
    
    private String name;
    private Integer stockQuantity;
    
    @Version  // ← JPA tự động tăng version mỗi lần UPDATE
    private Integer version;
}

// Service
@Service
@Transactional
public class InvoiceService {
    
    public void addProductToInvoice(UUID invoiceId, UUID productId, int quantity) {
        // Step 1: Read product (không lock)
        Product product = productRepo.findById(productId)
            .orElseThrow(() -> new NotFoundException("Product not found"));
        
        // Step 2: Check stock
        if (product.getStockQuantity() < quantity) {
            throw new InsufficientStockException("Chỉ còn " + product.getStockQuantity());
        }
        
        // Step 3: Update stock (JPA tự động check version)
        product.setStockQuantity(product.getStockQuantity() - quantity);
        productRepo.save(product);
        // ↑ SQL: UPDATE products SET stock_quantity=?, version=version+1 WHERE id=? AND version=?
        // Nếu version không khớp → OptimisticLockException
        
        // Step 4: Add to invoice
        InvoiceDetail detail = new InvoiceDetail(invoiceId, productId, quantity, product.getPrice());
        invoiceDetailRepo.save(detail);
    }
}

// Controller - Retry logic
@PostMapping("/invoices/{id}/items")
public ResponseEntity<?> addItem(@PathVariable UUID id, @RequestBody AddItemRequest req) {
    int maxRetries = 3;
    for (int i = 0; i < maxRetries; i++) {
        try {
            invoiceService.addProductToInvoice(id, req.getProductId(), req.getQuantity());
            return ResponseEntity.ok("Đã thêm vào hóa đơn");
        } catch (OptimisticLockException e) {
            if (i == maxRetries - 1) {
                return ResponseEntity.status(409).body("Sản phẩm vừa được bán hết, vui lòng chọn sản phẩm khác");
            }
            // Retry lần tiếp theo
        }
    }
}
```

**Tại sao dùng Optimistic?**
- ✅ Conflict ít xảy ra (xác suất 2 thu ngân bán cùng 1 món hàng cuối cùng < 1%)
- ✅ Performance cao (không lock row ở DB)
- ✅ Retry được (bán sản phẩm khác)

---

#### 1.2. Chống Trùng Lịch (Bookings) - DB Constraint + Pessimistic Lock

```sql
-- Migration: Unique constraint ở DB level
ALTER TABLE booking_details
ADD CONSTRAINT uq_booking_slot 
UNIQUE (booking_date, court_id, time_slot_id);
```

```java
// Service
@Service
@Transactional
public class BookingService {
    
    public BookingResponse createBooking(CreateBookingRequest req) {
        // Step 1: Lock row sân để kiểm tra (Pessimistic Lock)
        Court court = courtRepo.findByIdForUpdate(req.getCourtId())
            .orElseThrow(() -> new NotFoundException("Sân không tồn tại"));
        // SQL: SELECT * FROM courts WHERE id=? FOR UPDATE
        
        if (!court.getStatus().equals(CourtStatus.AVAILABLE)) {
            throw new BusinessException("Sân đang bảo trì");
        }
        
        // Step 2: Tạo booking
        Booking booking = new Booking();
        booking.setCustomerId(req.getCustomerId());
        booking.setStatus(BookingStatus.PENDING);
        booking.setCourtFee(calculateCourtFee(req));
        Booking saved = bookingRepo.save(booking);
        
        // Step 3: Tạo booking detail (DB constraint sẽ bắt duplicate)
        try {
            BookingDetail detail = new BookingDetail();
            detail.setBookingId(saved.getId());
            detail.setCourtId(req.getCourtId());
            detail.setTimeSlotId(req.getTimeSlotId());
            detail.setBookingDate(req.getDate());
            detail.setPrice(calculatePrice(court, timeSlot));
            bookingDetailRepo.save(detail);
            // ↑ Nếu trùng lịch → DB ném DuplicateKeyException
            
        } catch (DataIntegrityViolationException e) {
            if (e.getCause() instanceof ConstraintViolationException) {
                throw new BookingConflictException("Sân này đã có người đặt, vui lòng chọn giờ khác");
            }
            throw e;
        }
        
        return new BookingResponse(saved, detail);
    }
}

// Repository - Custom query với FOR UPDATE
@Repository
public interface CourtRepository extends JpaRepository<Court, UUID> {
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Court c WHERE c.id = :id")
    Optional<Court> findByIdForUpdate(@Param("id") UUID id);
}
```

**Tại sao KHÔNG dùng Optimistic?**
- ❌ Conflict xảy ra nhiều (giờ cao điểm, nhiều người đặt cùng lúc)
- ❌ Retry KHÔNG có ý nghĩa (sân đã mất, user phải chọn giờ khác)
- ❌ User experience tệ (đặt xong mới báo "Xin lỗi, đã có người đặt rồi")

**Lợi ích của DB Constraint + Pessimistic Lock:**
- ✅ Fail fast: Request thứ 2 fail ngay lập tức
- ✅ Không thể bypass (constraint ở DB, không thể bypass bằng code)
- ✅ User experience tốt hơn (biết ngay sân còn hay hết)

---

### 📊 So Sánh Hai Cách

#### Scenario: 2 requests đến cùng lúc

**Optimistic Locking (Products):**
```
Thread 1: Read product (stock=1, version=5)
Thread 2: Read product (stock=1, version=5)
Thread 1: UPDATE stock=0, version=6 WHERE version=5 ✅
Thread 2: UPDATE stock=0, version=6 WHERE version=5 ❌ (version đã là 6)
Thread 2: Retry → Read again (stock=0) → Throw InsufficientStockException
```

**Pessimistic Lock (Bookings):**
```
Thread 1: SELECT ... FOR UPDATE (lock row)  ✅
Thread 2: SELECT ... FOR UPDATE (WAIT)      ⏳
Thread 1: INSERT booking_detail             ✅
Thread 1: COMMIT (unlock)
Thread 2: Wake up, SELECT ... FOR UPDATE    ✅
Thread 2: INSERT booking_detail             ❌ Unique constraint violated
Thread 2: Rollback → Throw BookingConflictException
```

---

## 2. Spring Boot 3.x - Jakarta EE (Không Jakarta Persistence)

### ⚠️ Lưu Ý Quan Trọng

README chưa đề cập **Spring Boot version** → Cần rõ ràng:

```xml
<!-- pom.xml -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.0</version>  <!-- Hoặc 3.3.x -->
</parent>

<properties>
    <java.version>17</java.version>  <!-- Tối thiểu Java 17 -->
</properties>

<dependencies>
    <!-- ✅ ĐÚNG: Jakarta namespace -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    
    <!-- ✅ ĐÚNG: Validation Jakarta -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    
    <!-- ❌ SAI: Javax (chỉ dùng cho Spring Boot 2.x) -->
    <!-- <dependency> -->
    <!--     <groupId>javax.validation</groupId> -->
    <!--     <artifactId>validation-api</artifactId> -->
    <!-- </dependency> -->
</dependencies>
```

### 📝 Code Migration

```java
// ❌ SAI (Spring Boot 2.x / Java EE)
import javax.persistence.*;
import javax.validation.constraints.*;

// ✅ ĐÚNG (Spring Boot 3.x / Jakarta EE)
import jakarta.persistence.*;
import jakarta.validation.constraints.*;

@Entity
@Table(name = "customers")
public class Customer {
    @Id
    private UUID id;
    
    @NotBlank(message = "Tên không được để trống")  // jakarta.validation
    private String fullName;
    
    @Pattern(regexp = "^0\\d{9}$")  // jakarta.validation
    private String phone;
}
```

### 🔍 Kiểm Tra Dependencies Cũ

```bash
# Tìm dependencies còn dùng javax.*
mvn dependency:tree | grep javax

# Nếu thấy javax.* → cần thay thế hoặc bỏ
```

**Các lib thường gặp lỗi:**
- ❌ `springfox-swagger2` (chưa support Jakarta) → Dùng `springdoc-openapi-starter-webmvc-ui`
- ❌ `hibernate-validator` < 8.0 → Dùng >= 8.0
- ❌ Các lib cũ chưa migrate (kiểm tra issue tracker trước khi thêm)

---

## 3. Payment Gateway (VNPay/MoMo) - Webhook Handler Chặt Chẽ

### 🔐 Security Checklist

```java
@RestController
@RequestMapping("/api/webhooks")
@Slf4j
public class PaymentWebhookController {
    
    @Autowired
    private PaymentService paymentService;
    
    @Value("${vnpay.hash-secret}")
    private String vnpayHashSecret;
    
    /**
     * 1. VERIFY SIGNATURE (Bắt buộc)
     */
    @PostMapping("/vnpay")
    public ResponseEntity<String> handleVNPayCallback(@RequestParam Map<String, String> params) {
        // Step 1: Verify signature
        String receivedSignature = params.get("vnp_SecureHash");
        String calculatedSignature = VNPayUtil.calculateSignature(params, vnpayHashSecret);
        
        if (!receivedSignature.equals(calculatedSignature)) {
            log.error("Invalid signature from VNPay: {}", params);
            return ResponseEntity.status(400).body("INVALID_SIGNATURE");
        }
        
        // Step 2: Parse data
        String transactionId = params.get("vnp_TxnRef");  // booking_id
        String amount = params.get("vnp_Amount");  // Số tiền * 100
        String responseCode = params.get("vnp_ResponseCode");  // 00 = success
        
        /**
         * 2. IDEMPOTENT (Tránh double charge)
         */
        try {
            paymentService.processPayment(transactionId, amount, responseCode);
            return ResponseEntity.ok("SUCCESS");
        } catch (DuplicatePaymentException e) {
            // Webhook retry từ VNPay → trả SUCCESS luôn
            log.warn("Duplicate webhook for txn: {}", transactionId);
            return ResponseEntity.ok("SUCCESS");
        }
    }
}

@Service
@Transactional
public class PaymentService {
    
    public void processPayment(String bookingId, String amount, String responseCode) {
        // Step 1: Check duplicate (Idempotent)
        boolean exists = paymentTransactionRepo.existsByTransactionId(bookingId);
        if (exists) {
            throw new DuplicatePaymentException("Payment already processed");
        }
        
        // Step 2: Validate booking
        Booking booking = bookingRepo.findById(UUID.fromString(bookingId))
            .orElseThrow(() -> new NotFoundException("Booking not found"));
        
        if (!booking.getStatus().equals(BookingStatus.PENDING)) {
            throw new BusinessException("Booking không ở trạng thái PENDING");
        }
        
        // Step 3: Validate amount
        long receivedAmount = Long.parseLong(amount) / 100;  // VNPay gửi * 100
        if (receivedAmount != booking.getCourtFee().longValue()) {
            log.error("Amount mismatch: expected={}, received={}", booking.getCourtFee(), receivedAmount);
            throw new PaymentAmountMismatchException("Số tiền không khớp");
        }
        
        // Step 4: Update status
        if ("00".equals(responseCode)) {
            booking.setStatus(BookingStatus.PAID);
            bookingRepo.save(booking);
            
            // Save transaction
            PaymentTransaction txn = new PaymentTransaction();
            txn.setBookingId(booking.getId());
            txn.setTransactionId(bookingId);
            txn.setAmount(BigDecimal.valueOf(receivedAmount));
            txn.setPaymentMethod(PaymentMethod.VNPAY);
            txn.setStatus(PaymentStatus.SUCCESS);
            txn.setTransactionDate(LocalDateTime.now());
            paymentTransactionRepo.save(txn);
        } else {
            booking.setStatus(BookingStatus.PENDING);  // Giữ nguyên
            
            // Save failed transaction
            PaymentTransaction txn = new PaymentTransaction();
            txn.setBookingId(booking.getId());
            txn.setTransactionId(bookingId);
            txn.setAmount(BigDecimal.valueOf(receivedAmount));
            txn.setPaymentMethod(PaymentMethod.VNPAY);
            txn.setStatus(PaymentStatus.FAILED);
            txn.setNote("VNPay response code: " + responseCode);
            paymentTransactionRepo.save(txn);
        }
    }
}
```

### ⏱️ Timeout Handling (User đóng app giữa chừng)

```java
/**
 * 3. TIMEOUT HANDLING
 * User đóng app sau khi quét QR → Không nhận được callback
 * → Backend cần polling hoặc expire booking
 */
@Scheduled(fixedRate = 300000)  // 5 phút
public void expirePendingBookings() {
    LocalDateTime threshold = LocalDateTime.now().minusMinutes(15);
    
    List<Booking> expiredBookings = bookingRepo.findByStatusAndCreatedAtBefore(
        BookingStatus.PENDING, threshold
    );
    
    for (Booking booking : expiredBookings) {
        // Check với VNPay xem có thanh toán chưa
        boolean isPaid = vnpayService.queryTransaction(booking.getId().toString());
        
        if (isPaid) {
            // User đã thanh toán nhưng webhook bị lỗi → cập nhật
            booking.setStatus(BookingStatus.PAID);
        } else {
            // User bỏ dở → xóa booking (giải phóng slot)
            bookingRepo.delete(booking);
        }
    }
}
```

---

## 4. Real-time cho POS - Sync Trạng Thái Sân

### ❓ Vấn Đề

README viết: *"Web Admin: ReactJS (Dành cho Chủ sân, Quản lý, Thu ngân)"*

**Scenario:**
```
17:00:00 - Thu ngân A mở màn hình "Danh sách sân"
          → Sân 5 đang trống
17:00:05 - Khách đặt sân 5 qua Mobile App
17:00:10 - Thu ngân A click "Đặt sân 5 cho khách vãng lai"
          → ??? Sân 5 đã bị đặt rồi!
```

---

### 🎯 Giải Pháp (Theo Mức Độ Phức Tạp)

#### 4.1. ⭐ Simple - Polling (Đủ cho đồ án)

```javascript
// web-admin/src/components/CourtAvailability.jsx
import { useState, useEffect } from 'react';

export default function CourtAvailability({ date, timeSlotId }) {
  const [courts, setCourts] = useState([]);
  
  useEffect(() => {
    // Polling mỗi 5 giây
    const interval = setInterval(async () => {
      const res = await fetch(`/api/courts/availability?date=${date}&timeSlotId=${timeSlotId}`);
      const data = await res.json();
      setCourts(data);
    }, 5000);
    
    return () => clearInterval(interval);
  }, [date, timeSlotId]);
  
  return (
    <div className="grid grid-cols-5 gap-4">
      {courts.map(court => (
        <CourtCard 
          key={court.id} 
          court={court} 
          available={court.available}  // Cập nhật realtime mỗi 5s
        />
      ))}
    </div>
  );
}
```

**Ưu điểm:**
- ✅ Đơn giản, không cần infrastructure thêm
- ✅ Đủ cho đồ án (latency 5s chấp nhận được)

**Nhược điểm:**
- ⚠️ Latency 5-10s
- ⚠️ Tốn băng thông (nhưng không đáng kể)

---

#### 4.2. ⭐⭐ Better - WebSocket (STOMP)

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

```java
// Backend: WebSocket config
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
    
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*")
            .withSockJS();
    }
}

// Emit event khi có booking mới
@Service
public class BookingService {
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    public BookingResponse createBooking(CreateBookingRequest req) {
        // ... tạo booking ...
        
        // Emit event
        messagingTemplate.convertAndSend(
            "/topic/bookings", 
            new BookingCreatedEvent(booking.getId(), req.getCourtId(), req.getDate())
        );
        
        return response;
    }
}
```

```javascript
// Frontend: Subscribe WebSocket
import SockJS from 'sockjs-client';
import { Stomp } from '@stomp/stompjs';

useEffect(() => {
  const socket = new SockJS('http://localhost:8080/ws');
  const stompClient = Stomp.over(socket);
  
  stompClient.connect({}, () => {
    stompClient.subscribe('/topic/bookings', (message) => {
      const event = JSON.parse(message.body);
      // Cập nhật UI ngay lập tức
      setCourts(prev => prev.map(c => 
        c.id === event.courtId ? { ...c, available: false } : c
      ));
    });
  });
  
  return () => stompClient.disconnect();
}, []);
```

**Ưu điểm:**
- ✅ Realtime (latency < 100ms)
- ✅ Tiết kiệm băng thông (chỉ gửi khi có thay đổi)

**Nhược điểm:**
- ⚠️ Phức tạp hơn polling
- ⚠️ Cần handle reconnection

---

#### 4.3. ⭐⭐⭐ Overkill - Redis Pub/Sub

**KHÔNG CẦN THIẾT** cho đồ án:
- Redis Pub/Sub dành cho hệ thống multi-server (load balancer)
- Đồ án chỉ 1 server → WebSocket đủ rồi

---

### 📊 Đề Xuất Cho Đồ Án

| Phương pháp | Độ phức tạp | Latency | Đề xuất |
|-------------|-------------|---------|---------|
| Polling 5s | ⭐ | 5s | ✅ **Dùng cho đồ án** (đơn giản, đủ dùng) |
| WebSocket | ⭐⭐ | <100ms | Nếu muốn học thêm |
| Redis Pub/Sub | ⭐⭐⭐ | <50ms | Không cần |

---

## 5. React Native - Offline Mode

### 📱 Strategy

```javascript
// mobile-app/src/services/courtService.js
import AsyncStorage from '@react-native-async-storage/async-storage';

const CACHE_KEY = 'courts_cache';
const CACHE_TTL = 3600000; // 1 hour

export async function getCourts() {
  try {
    // Try fetch from server
    const res = await fetch('https://api.example.com/courts', {
      timeout: 5000  // 5s timeout
    });
    const data = await res.json();
    
    // Cache to AsyncStorage
    await AsyncStorage.setItem(CACHE_KEY, JSON.stringify({
      data,
      timestamp: Date.now()
    }));
    
    return data;
    
  } catch (error) {
    // Network error → fallback to cache
    const cached = await AsyncStorage.getItem(CACHE_KEY);
    if (cached) {
      const { data, timestamp } = JSON.parse(cached);
      const age = Date.now() - timestamp;
      
      if (age < CACHE_TTL) {
        return data;  // Cache còn mới
      }
    }
    
    throw new Error('Không có kết nối mạng và cache đã hết hạn');
  }
}
```

```javascript
// UI component
export default function CourtListScreen() {
  const [courts, setCourts] = useState([]);
  const [isOffline, setIsOffline] = useState(false);
  
  useEffect(() => {
    loadCourts();
  }, []);
  
  async function loadCourts() {
    try {
      const data = await getCourts();
      setCourts(data);
      setIsOffline(false);
    } catch (error) {
      setIsOffline(true);
      Alert.alert('Lỗi', 'Không thể tải dữ liệu. Vui lòng kiểm tra kết nối.');
    }
  }
  
  return (
    <View>
      {isOffline && (
        <Banner type="warning">
          Đang sử dụng dữ liệu offline. Một số tính năng bị hạn chế.
        </Banner>
      )}
      
      <FlatList 
        data={courts}
        renderItem={({ item }) => (
          <CourtCard 
            court={item} 
            onBook={() => {
              if (isOffline) {
                Alert.alert('Lỗi', 'Cần kết nối mạng để đặt sân');
                return;
              }
              navigation.navigate('Booking', { courtId: item.id });
            }}
          />
        )}
      />
    </View>
  );
}
```

---

## 📝 Tổng Kết & Checklist

### ✅ Các Điểm Đúng Trong README

1. ✅ **PostgreSQL + Constraints:** Đúng hướng
2. ✅ **UUID:** Bảo mật tốt
3. ✅ **Snapshot Price:** Đúng thiết kế
4. ✅ **Optimistic Locking (cho Products):** Đúng use case

### ⚠️ Các Điểm Cần Bổ Sung

| # | Vấn Đề | Giải Pháp | Ưu Tiên |
|---|--------|-----------|---------|
| 1 | Chống trùng lịch cần Pessimistic Lock, không phải Optimistic | DB Constraint + `SELECT FOR UPDATE` | 🔴 Cao |
| 2 | README chưa ghi rõ Spring Boot 3.x → cần Jakarta EE | Thêm vào `pom.xml`, document migration | 🟡 Trung Bình |
| 3 | Payment webhook cần verify signature + idempotent | Implement theo code mẫu trên | 🔴 Cao |
| 4 | POS thiếu realtime sync | Dùng Polling 5s (đủ cho đồ án) | 🟢 Thấp |
| 5 | Mobile app thiếu offline handling | Cache với AsyncStorage | 🟡 Trung Bình |

---

## 🚀 Action Items

### Cần làm ngay:
1. **Sửa README:** Thêm rõ Spring Boot 3.2+, Java 17+
2. **Thêm Pessimistic Lock:** Cho booking service
3. **Implement Payment Webhook:** Theo checklist security

### Có thể làm sau:
4. WebSocket cho realtime (nếu có thời gian)
5. Offline mode cho mobile app

---

Bạn muốn tôi:
- **A) Viết code mẫu đầy đủ** cho Booking Service (Pessimistic Lock)?
- **B) Viết file README.md mới** với đầy đủ lưu ý này?
- **C) Tạo SQL migration script** với đầy đủ constraints?
