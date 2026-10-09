package com.bcm.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Entity @Table(name="time_slots") @Getter @Setter
public class TimeSlot extends BaseEntity {
@Column(name="start_time",nullable=false) private java.time.LocalTime startTime;
@Column(name="end_time",nullable=false) private java.time.LocalTime endTime;
@Column(name="price_multiplier",nullable=false,precision=3,scale=2) private java.math.BigDecimal priceMultiplier;
}
