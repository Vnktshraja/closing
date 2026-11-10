package com.multiplier.reactive.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(value = "fno_high_low", schema = "reactive")
public class FNOHighLow {

    @Id
    @Column("id")
    private Long id;

    @Column("trading_symbol")
    private String tradingSymbol;

    @Column("previous_high")
    private double previousHigh;

    @Column("previous_low")
    private double previousLow;

    @Column("previous_open")
    private double previousOpen;

    @Column("previous_close")
    private double previousClose;

    @Column("weekly_high")
    private double weeklyHigh;

    @Column("weekly_low")
    private double weeklyLow;

    @Column("monthly_high")
    private double monthlyHigh;

    @Column("monthly_low")
    private double monthlyLow;

    @Column("created_date")
    private LocalDate createdDate;
}
