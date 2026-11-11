package com.vnkst.closing.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderRequest {
    private String trading_symbol;
    private int quantity;
    private double price;
    private double trigger_price;
    private String validity;
    private String exchange;
    private String segment;
    private String product;
    private String order_type;
    private String transaction_type;
    private String order_reference_id;
}