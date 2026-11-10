package com.multiplier.reactive.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;

@Data
@Table(value = "daily_record", schema = "reactive")
public class DailyRecord {

    @Id
    private Long id;

    @Column("symbol")
    private String symbol;

    @Column("change_in_percent")
    private double changeInPercent;

    @Column("change_in_value")
    private double changeInValue;

    @Column("ltp")
    private double ltp;

    @Column("previous_close")
    private double previousClose;

    @Column("open")
    private double open;

    @Column("close")
    private double close;

    @Column("high")
    private double high;

    @Column("low")
    private double low;

    @Column("volume")
    private long volume;

    @Column("record_date")
    private LocalDate recordDate;

    @Column("ema_9")
    private double ema9;

    @Column("ema_20")
    private double ema20;

    @Column("pivot")
    private double pivot;

    @Column("resistance_1")
    private double resistance1;

    @Column("support_1")
    private double support1;

    @Column("resistance_2")
    private double resistance2;

    @Column("support_2")
    private double support2;

    @Column("resistance_3")
    private double resistance3;

    @Column("support_3")
    private double support3;

    @Column("weekly_high")
    private double weeklyHigh;

    @Column("weekly_low")
    private double weeklyLow;

    @Column("monthly_high")
    private double monthlyHigh;

    @Column("monthly_low")
    private double monthlyLow;
}