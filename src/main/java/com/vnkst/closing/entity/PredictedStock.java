package com.vnkst.closing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "predicted_stocks", schema = "reactive")
public class PredictedStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "prediction_date")
    private LocalDate predictionDate;

    @Column(name = "analysis_type")
    private String analysisType;

    @Column(name = "option_type")
    private String optionType;

    @Column(name = "entry_price")
    private double entryPrice;

    @Column(name = "target_price")
    private Double targetPrice;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "details")
    private String details;

    @Column(name = "volume")
    private long volume;

    @Column(name = "created_at")
    private LocalDate createdAt;

    // ── 52W High/Low live fields (not persisted in DB) ────────────────────────
    @Transient private String name;           // Display name
    @Transient private double price;          // LTP (Last Traded Price)
    @Transient private double changePct;      // % change on the day
    @Transient private double high52;         // 52-week high
    @Transient private double low52;          // 52-week low
    @Transient private double proximityToHigh; // % away from 52W high
    @Transient private double proximityToLow;  // % away from 52W low
    @Transient private String status;         // "NEAR HIGH" | "NEAR LOW" | "NORMAL"
    @Transient private String sector;         // Sector name

    // ── Buyable calculation fields (not persisted in DB) ──────────────────────
    @Transient private Integer lotSize;       // F&O or equity lot size
    @Transient private Double costPerLot;     // Margin required: lotSize * price
    @Transient private Boolean buyable;       // Whether affordable within available cash
}

