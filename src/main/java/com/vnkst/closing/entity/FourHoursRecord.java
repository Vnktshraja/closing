package com.vnkst.closing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Entity
@Table(name = "four_hours_record", schema = "reactive")
public class FourHoursRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "four_id")
    private Long fourId;

    @Transient
    private List<FourHoursCandles> candles = new ArrayList<>();

    @Column(name = "start_time")
    private String startTime;

    @Column(name = "end_time")
    private String endTime;

    @Column(name = "entered_date")
    private LocalDate enteredDate;

    @Column(name = "updated_time")
    private LocalTime updatedTime;

    @Column(name = "interval_in_minutes")
    private Integer intervalInMinutes;

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "trend")
    private String trend;
}
