package com.vnkst.closing.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "orders", schema = "reactive")
public class Orders implements Persistable<String> {
    @Id
    @Column(name = "groww_order_id")
    private String groww_order_id;

    @Column(name = "is_new")
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

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "order_state")
    private String order_state;

    @Column(name = "options")
    private String options;

    @Column(name = "order_reference_id")
    private String order_reference_id;

    @Column(name = "quantity")
    private int quantity;

    @Column(name = "bought_price")
    private double boughtPrice;

    @Column(name = "sold_price")
    private double soldPrice;

    @Column(name = "segment")
    private String segment;

    @Column(name = "transaction_type")
    private String transaction_type;

    @Column(name = "pnl")
    private double pnl;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Column(name = "order_placed_time")
    private LocalDateTime order_placed_Time;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Column(name = "order_closed_time")
    private LocalDateTime order_closed_Time;

    @Column(name = "transaction_date")
    private LocalDate transaction_date;

    @Column(name = "remark")
    private String remark;

    @Column(name = "order_type")
    private String order_type;

    @Column(name = "closing_reason")
    private String closing_reason;

    @Column(name = "status")
    private String status;
}
