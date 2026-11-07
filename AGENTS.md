# Coding agent — Booking Engine

## Phạm vi và thứ tự đọc

- MUST làm việc theo scope Thành viên 3 trên branch `feature/booking-engine`.
- MUST đọc file này, `SKILL.md`, `db/migration.sql`, `plans.md`, source liên quan,
  `backend/pom.xml` và `application.yml` trước khi sửa; kiểm tra Git diff hiện có.
- MUST dùng source/build config để xác nhận convention; README/docs chỉ là tham khảo.
  Phân công và nội dung Mobile cũ không mở rộng scope hiện tại.
- MUST chỉ sửa Booking Engine; scope nghiệp vụ chi tiết nằm trong `SKILL.md`.
- MUST ưu tiên backend -> backend tests -> API contract/Swagger -> ReactJS Web UI.
- MUST chỉ xây Web App; MUST NOT dùng React Native, tạo mobile app hoặc API riêng cho mobile.
- MUST NOT sửa module thành viên khác ngoài reference hoặc tích hợp tối thiểu bắt buộc;
  giải thích dependency và diff dùng chung khi cần.

## Convention đã xác nhận từ repository

- `backend/pom.xml`: Spring Boot 3.2.5, Java source/target 17, Maven;
  README ghi Java 21 nhưng không đổi build target chỉ theo README.
- Base package `com.bcm`; hiện có `controller`, `entity`, `dto.response`,
  `exception`, `config`. Chưa có service/repository và entity nghiệp vụ.
- Khi task cần, thêm layer `com.bcm.service`, `com.bcm.repository`, DTO request
  trong `com.bcm.dto.request`; giữ cách tổ chức theo layer hiện có.
- Dùng `jakarta.persistence`/`jakarta.validation`, JPA, Lombok như source hiện tại;
  tên class PascalCase, thuộc tính camelCase, `@Table`/`@Column` map SQL snake_case.
- Dùng `ApiResponse<T>` và `GlobalExceptionHandler` cho API Web;
  `BadRequestException` -> 400, `ResourceNotFoundException` -> 404,
  `DuplicateResourceException` -> 409. Đây là các class đang tồn tại.
- Controller khai báo path tương đối; context path `/api` đã ở `application.yml`.
  Callback gateway trả acknowledgment theo contract provider khi cần.
- `HealthController` dùng `@Tag`/`@Operation`; tái sử dụng Springdoc hiện có.
- Config ở `backend/src/main/resources/application.yml` và `com.bcm.config`.
- JPA auditing đã bật; `open-in-view: false`, nên map response trong service khi cần dữ liệu lazy.
- `BaseEntity` hiện map cả `deleted_at`; chỉ kế thừa nếu bảng có đủ column tương ứng.
  Không refactor base class dùng chung để ép entity Booking kế thừa.

## Schema và thay đổi tối thiểu

- MUST coi `db/migration.sql` là SOURCE OF TRUTH của table/column/FK/index/CHECK/status.
- MUST NOT sửa schema, thêm migration hoặc thêm mapped field/status ngoài schema
  khi chưa có yêu cầu rõ ràng của người dùng.
- MUST giữ `ddl-auto: validate` và SQL init `never`; không dùng Hibernate tự tạo/sửa DB.
- MUST tuân thủ invariants trong `SKILL.md`, bao gồm kiểu tiền, expiry và payment target.
- MUST tái sử dụng mapping master data/reference nếu đã có khi bắt đầu task;
  nếu thiếu, chỉ map/query phần bắt buộc, không triển khai CRUD/Auth/Invoice.
- SHOULD giữ minimal diff; không chuyển architecture sang DDD/CQRS/Clean/Hexagonal.
- MUST NOT tạo class/file rỗng, scaffold cho phase chưa triển khai hoặc dependency không cần.

## Transaction và security

- MUST đặt transaction boundary ở service và bảo đảm rollback đầy đủ khi lỗi.
- MUST bảo vệ concurrency bằng pessimistic lock trên row tồn tại và unique index hiện có;
  không dựa riêng vào availability pre-check hoặc JVM lock.
- MUST phối hợp callback/expiration bằng khóa và kiểm tra lại status/deadline;
  tuân thủ thao tác EXPIRED + DELETE details atomic trong `SKILL.md`.
- MUST xử lý lỗi unique slot thành business error 409, kể cả khi lỗi xảy ra tại commit.
- MUST verify callback và bảo đảm idempotency; không lấy kết quả redirect từ browser làm bằng chứng.
- MUST lấy gateway credentials từ environment/Spring config, không hard-code/log secret.
- Security hiện `permitAll()` và chưa có JWT flow. Không tự viết Auth;
  dùng identity/ownership từ tích hợp Auth khi có, ghi rõ limitation nếu chưa có.

## Kiểm tra và hoàn thành task

- Test framework đã có: Spring Boot Test (JUnit 5, Mockito, AssertJ), Spring Security Test.
  Hiện chưa có `src/test` hoặc dependency Testcontainers; chỉ thêm khi task thực sự cần.
- MUST test integration/concurrency trên PostgreSQL với schema `db/migration.sql`;
  dùng DB test riêng, không thay bằng mock/H2 để chứng minh DB locking/unique index.
- MUST test hành vi task đã sửa; concurrency bắt buộc có hai request/transaction độc lập,
  cùng khởi chạy có kiểm soát, đúng một thành công và một lỗi slot không khả dụng.
- MUST chạy tests/build sau thay đổi code: `mvn -f backend/pom.xml verify`;
  repo hiện không có Maven wrapper. Có thể chạy test mục tiêu trước.
- Với thay đổi chỉ tài liệu: kiểm tra nội dung/schema, phạm vi file và `git diff --check`.
- MUST báo lệnh đã chạy, kết quả và blocker nếu không chạy được; không tuyên bố test pass khi chưa chạy.
- Chỉ hoàn thành khi acceptance criteria của task đạt, không có regression đã biết,
  diff đúng phạm vi và API contract/limitation liên quan được ghi rõ.
- Chỉ đánh dấu checklist đã làm sau khi kiểm chứng code/test thực tế.
