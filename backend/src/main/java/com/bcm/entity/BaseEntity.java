package com.bcm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * BaseEntity - Abstract base class cho tất cả entities
 *
 * Cung cấp các fields chung:
 * - id (UUID): Primary key
 * - created_at: Thời gian tạo (auto-populate)
 * - updated_at: Thời gian cập nhật cuối (auto-update)
 * - deleted_at: Thời gian xóa mềm (soft delete)
 *
 * Cách dùng:
 * public class User extends BaseEntity {
 *     // Additional fields...
 * }
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    /**
     * Primary Key - UUID
     * Tự động generate khi tạo entity mới
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Thời gian tạo
     * Tự động set khi entity được persist lần đầu
     */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Thời gian cập nhật cuối
     * Tự động update mỗi khi entity thay đổi
     */
    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Thời gian xóa mềm (Soft Delete)
     * null = chưa xóa
     * non-null = đã xóa
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * Lifecycle callback - Trước khi persist
     * Đảm bảo created_at và updated_at được set
     */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    /**
     * Lifecycle callback - Trước khi update
     * Tự động update updated_at
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Soft delete - Đánh dấu entity là đã xóa
     * Không xóa khỏi database
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * Kiểm tra entity đã bị xóa mềm chưa
     */
    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    /**
     * Restore entity đã xóa mềm
     */
    public void restore() {
        this.deletedAt = null;
    }
}
