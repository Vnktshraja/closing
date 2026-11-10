package com.multiplier.reactive.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(value = "orders", schema = "reactive")
public class Orders implements Persistable<String> {
    @Id
    @Column("groww_order_id")
    private String groww_order_id;

    @Column("is_new")
    private boolean isNew = false;

    @Override
    @Transient
    public String getId() {
        return this.groww_order_id;
    }

    @Override
    @Transient
    public boolean isNew() {
        return this.isNew;
    }

    public void setNew(boolean isNew) {
        this.isNew = isNew;
    }

    @Column("symbol")
    private String symbol;

    @Column("order_state")
    private String order_state;

    @Column("options")
    private String options;

    @Column("order_reference_id")
    private String order_reference_id;

    @Column("quantity")
    private int quantity;

    @Column("bought_price")
    private double boughtPrice;

    @Column("sold_price")
    private double soldPrice;

    @Column("segment")
    private String segment;

    @Column("transaction_type")
    private String transaction_type;

    @Column("pnl")
    private double pnl;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Column("order_placed_time")
    private LocalDateTime order_placed_Time;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Column("order_closed_time")
    private LocalDateTime order_closed_Time;

    @Column("transaction_date")
    private LocalDate transaction_date;

    @Column("remark")
    private String remark;

    @Column("order_type")
    private String order_type;

    @Column("closing_reason")
    private String closing_reason;

    @Column("status")
    private String status;
}
