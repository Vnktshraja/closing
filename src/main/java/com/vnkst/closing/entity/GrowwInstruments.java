package com.vnkst.closing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.domain.Persistable;

import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Entity
@Table(name = "fnogrowwcsv", schema = "reactive")
public class GrowwInstruments implements Persistable<String> {

    @Id
    @Column(name = "exchange_token")
    private String exchangeToken;

    @Column(name = "exchange")
    private String exchange;

    @Column(name = "trading_symbol")
    private String tradingSymbol;

    @Column(name = "instrument_type")
    private String instrumentType;

    @Column(name = "underlying_symbol")
    private String underlyingSymbol;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "strike_price")
    private double strikePrice;

    @Column(name = "lot_size")
    private int lotSize;

    @Column(name = "internal_trading_symbol")
    private String internalTradingSymbol;

    @Column(name = "created_date")
    private LocalDate createdDate;

    @Override
    @Transient
    public String getId() {
        return exchangeToken;
    }

    @Override
    @Transient
    public boolean isNew() {
        return true;
    }
}

