package com.vnkst.closing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "fno_high_low", schema = "reactive")
public class FNOHighLow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "trading_symbol")
    private String tradingSymbol;

    @Column(name = "previous_high")
    private double previousHigh;

    @Column(name = "previous_low")
    private double previousLow;

    @Column(name = "previous_open")
    private double previousOpen;

    @Column(name = "previous_close")
    private double previousClose;

    @Column(name = "weekly_high")
    private double weeklyHigh;

    @Column(name = "weekly_low")
    private double weeklyLow;

    @Column(name = "monthly_high")
    private double monthlyHigh;

    @Column(name = "monthly_low")
    private double monthlyLow;

    @Column(name = "created_date")
    private LocalDate createdDate;
}
