package com.vnkst.closing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;

@Data
@Entity
@Table(name = "daily_record", schema = "reactive")
public class DailyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "change_in_percent")
    private double changeInPercent;

    @Column(name = "change_in_value")
    private double changeInValue;

    @Column(name = "ltp")
    private double ltp;

    @Column(name = "previous_close")
    private double previousClose;

    @Column(name = "open")
    private double open;

    @Column(name = "close")
    private double close;

    @Column(name = "high")
    private double high;

    @Column(name = "low")
    private double low;

    @Column(name = "volume")
    private long volume;

    @Column(name = "record_date")
    private LocalDate recordDate;

    @Column(name = "ema_9")
    private double ema9;

    @Column(name = "ema_20")
    private double ema20;

    @Column(name = "pivot")
    private double pivot;

    @Column(name = "resistance_1")
    private double resistance1;

    @Column(name = "support_1")
    private double support1;

    @Column(name = "resistance_2")
    private double resistance2;

    @Column(name = "support_2")
    private double support2;

    @Column(name = "resistance_3")
    private double resistance3;

    @Column(name = "support_3")
    private double support3;

    @Column(name = "weekly_high")
    private double weeklyHigh;

    @Column(name = "weekly_low")
    private double weeklyLow;

    @Column(name = "monthly_high")
    private double monthlyHigh;

    @Column(name = "monthly_low")
    private double monthlyLow;
}