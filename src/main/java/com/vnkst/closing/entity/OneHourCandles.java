package com.vnkst.closing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Entity
@Table(name = "one_hour_candles", schema = "reactive")
public class OneHourCandles {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "one_candle_id")
    private Long oneCandleId;

    @Column(name = "one_record_id")
    private Long oneRecordId;

    @Column(name = "timestamp")
    private String timestamp;

    @Column(name = "open_price")
    private double openPrice;

    @Column(name = "high_price")
    private double highPrice;

    @Column(name = "low_price")
    private double lowPrice;

    @Column(name = "close_price")
    private double closePrice;

    @Column(name = "colour")
    private String colour;

    @Column(name = "volume")
    private long volume;

    @Column(name = "count")
    private int count;

    @Column(name = "trend")
    private String trend;
}
