package com.vnkst.closing.service;


import com.vnkst.closing.cache.DataCache;
import com.vnkst.closing.repository.OrdersRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
public class OrderPlacingService {

    @Autowired
    private ClientHttp clientHttp;

    @Autowired
    OrdersRepository ordersRepository;

    @Autowired
    private DataCache dataCache;

    // Both Equity or Fno order we can create on this method.
    public Mono<OrderRequest> placeOrders(String symbol, int quantity, double price, String orderType) {
        return Mono.defer(() -> {
            String segment = isFno(symbol) ? FNO : CASH;
            String product = isFno(symbol) ? FNO_PRODUCT : EQUITY_PRODUCT;
            OrderRequest orderRequest = getOrderRequestPayload(symbol, quantity, price, segment, product, BUY);
            return clientHttp.placeOrderOnGroww(orderRequest)
                    .flatMap(orderResponse -> {
                        if (orderResponse == null || orderResponse.getPayload() == null) {
                            String errDetails = (orderResponse != null && orderResponse.getError() != null)
                                    ? String.format("code=%s, msg=%s", orderResponse.getError().getCode(), orderResponse.getError().getMessage())
                                    : "N/A";
                            log.error("Failed to place buy order on Groww for {}: orderResponse or payload is null (Status: {}, Error: {})",
                                    symbol, orderResponse != null ? orderResponse.getStatus() : "NULL", errDetails);
                            return Mono.just(orderRequest);
                        }
                        Orders newOrder = new Orders();
                        newOrder.setNew(true);
                        newOrder.setSymbol(symbol);
                        return generateOrderResponse(newOrder, orderResponse.getStatus(), price, segment, BUY, quantity,
                                orderResponse.getPayload().getGroww_order_id(),
                                ACTIVE, orderResponse.getPayload().getOrder_reference_id(), orderType, null)
                                .flatMap(orders -> {
                                    // Populate cache immediately with new active order
                                    Map<String, Object> valueMap = new HashMap<>();
                                    valueMap.put(PRICE, price);
                                    valueMap.put(QUANTITY, quantity);
                                    valueMap.put("order_type", orderType);
                                    dataCache.addActiveSymbols(symbol, valueMap);

                                    return ordersRepository.save(orders).thenReturn(orderRequest);
                                });
                    });
        });
    }

    public Mono<String> sellOrders(String symbol, double price, String closingReason) {
        return Mono.defer(() -> {
            if (!isMarketHours()) {
                log.warn("[SellOrder] Attempted to sell order for {} outside market hours. Aborting exit order execution.", symbol);
                return Mono.just("outside_market_hours");
            }
            if (!dataCache.alreadyExist(symbol)) {
                return Mono.just("error");
            }
            String segment = isFno(symbol) ? FNO : CASH;
            String product = isFno(symbol) ? FNO_PRODUCT : EQUITY_PRODUCT;
            return ordersRepository.getOpenOrder(symbol, segment, ACTIVE)
                    .switchIfEmpty(ordersRepository.getOpenOrderBySymbolAndStatus(symbol, ACTIVE).next())
                    .switchIfEmpty(Mono.defer(() -> {
                        Map<String, Object> info = dataCache.getActiveSymbols().get(symbol);
                        int qty = (info != null && info.get(QUANTITY) != null) ? ((Number) info.get(QUANTITY)).intValue() : 0;
                        double bought = (info != null && info.get(PRICE) != null) ? ((Number) info.get(PRICE)).doubleValue() : price;
                        if (qty > 0) {
                            Orders fallback = new Orders();
                            fallback.setSymbol(symbol);
                            fallback.setSegment(segment);
                            fallback.setQuantity(qty);
                            fallback.setBoughtPrice(bought);
                            fallback.setOrder_state(ACTIVE);
                            fallback.setOrder_type(AUTO);
                            fallback.setNew(true);
                            return Mono.just(fallback);
                        }
                        log.warn("[SellOrder] No active order record found for {} in DB or cache", symbol);
                        return Mono.empty();
                    }))
                    .flatMap(existingOrder -> {
                        int quantity = existingOrder.getQuantity();
                        OrderRequest orderRequest = getOrderRequestPayload(symbol, quantity, price, segment, product,
                                SELL);
                        if (Objects.nonNull(orderRequest.getTrading_symbol())) {
                            return clientHttp.placeOrderOnGroww(orderRequest)
                                    .flatMap(orderResponse -> {
                                        if (orderResponse == null || orderResponse.getPayload() == null) {
                                            String errDetails = (orderResponse != null && orderResponse.getError() != null)
                                                    ? String.format("code=%s, msg=%s", orderResponse.getError().getCode(), orderResponse.getError().getMessage())
                                                    : "N/A";
                                            log.error("Failed to sell order on Groww for {}: orderResponse or payload is null (Status: {}, Error: {})",
                                                    symbol, orderResponse != null ? orderResponse.getStatus() : "NULL", errDetails);
                                            return Mono.just("error");
                                        }
                                        return generateOrderResponse(existingOrder, orderResponse.getStatus(), price,
                                                segment, SELL, quantity, orderResponse.getPayload().getGroww_order_id(),
                                                CLOSED, orderResponse.getPayload().getOrder_reference_id(), null, closingReason)
                                                .flatMap(orders -> {
                                                    // Evict from cache immediately
                                                    dataCache.removeSymbols(symbol);

                                                    return ordersRepository.save(orders).thenReturn("success");
                                                });
                                    });
                        }
                        return Mono.just("error");
                    })
                    .defaultIfEmpty("error");
        });
    }

    public Mono<String> modifyOrders(String symbol, double price) {
        return Mono.defer(() -> {
            if (!dataCache.alreadyExist(symbol)) {
                return Mono.just("success");
            }
            String segment = isFno(symbol) ? FNO : CASH;
            return ordersRepository.getOpenOrder(symbol, segment, ACTIVE)
                    .flatMap(existingOrder -> {
                        int quantity = existingOrder.getQuantity();
                        String grow_order_id = existingOrder.getGroww_order_id();
                        try {
                            String payload = getModifyPayload(quantity, grow_order_id, price);
                            return clientHttp.modifyOrder(payload)
                                    .doOnNext(orderResponse -> {
                                        log.info("Order modified successfully...!" + orderResponse);
                                        dataCache.updateActiveSymbolPrice(symbol, price);
                                    })
                                    .thenReturn("success");
                        } catch (JsonProcessingException e) {
                            return Mono.error(e);
                        }
                    })
                    .defaultIfEmpty("success");
        });
    }

    private String getModifyPayload(int quantity, String grow_order_id, double price) throws JsonProcessingException {
        Map<String, Object> modifyRequestPayload = new LinkedHashMap<>();
        modifyRequestPayload.put("quantity", quantity);
        modifyRequestPayload.put("price", price);
        modifyRequestPayload.put("trigger_price", price);
        modifyRequestPayload.put("order_type", "LIMIT");
        modifyRequestPayload.put("segment", CASH);
        modifyRequestPayload.put("groww_order_id", grow_order_id);
        return new ObjectMapper().writeValueAsString(modifyRequestPayload);
    }

    private Mono<Orders> generateOrderResponse(Orders orders, String status, double price, String segment,
            String transactionType,
            int quantity, String growwOrderId, String state, String orderReferenceId, String orderType,
            String closingReason) {
        return Mono.fromCallable(() -> {
            orders.setStatus(status);
            orders.setSegment(segment);
            orders.setTransaction_type(transactionType);
            if (orderType != null) {
                orders.setOrder_type(orderType);
            } else if (orders.getOrder_type() == null) {
                orders.setOrder_type(AUTO);
            }
            if (closingReason != null) {
                orders.setClosing_reason(closingReason);
            }
            if (FNO.equalsIgnoreCase(segment)) {
                String symbol = orders.getSymbol();
                String lastTwo = symbol.substring(symbol.length() - 2);
                orders.setOptions(lastTwo);
            }
            orders.setQuantity(quantity);
            if (orders.getGroww_order_id() == null) {
                orders.setGroww_order_id(growwOrderId);
            } else {
                orders.setNew(false);
            }
            orders.setOrder_state(state);
            orders.setOrder_reference_id(orderReferenceId);
            if (SELL.equals(transactionType)) {
                double boughtPrice = orders.getBoughtPrice();
                if (boughtPrice > price) {
                    double loss = -((boughtPrice - price) * quantity);
                    orders.setPnl(loss);
                    orders.setRemark(LOSS);
                } else if (boughtPrice == price) {
                    orders.setPnl(0.00);
                    orders.setRemark(LOSS + " BROKERAGE");
                } else {
                    double profit = (price - boughtPrice) * quantity;
                    orders.setPnl(profit);
                    orders.setRemark(PROFIT);
                }
                orders.setOrder_closed_Time(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
                orders.setSoldPrice(price);
            } else {
                orders.setBoughtPrice(price);
                orders.setOrder_placed_Time(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            }
            return orders;
        });
    }

    private OrderRequest getOrderRequestPayload(String symbol, int quantity, double price, String segment,
            String product, String transactionType) {
        OrderRequest orderRequest = new OrderRequest();
        orderRequest.setTrading_symbol(symbol);
        orderRequest.setQuantity(quantity);
        orderRequest.setPrice(price);
        orderRequest.setTrigger_price(price);
        orderRequest.setValidity("DAY");
        orderRequest.setExchange("NSE");
        orderRequest.setSegment(segment);
        // CNC -> Cash and Carry
        // MIS -> for Intraday Square-off
        // NRML – Normal (Carry Forward Position) Used for Futures & Options
        orderRequest.setProduct(product);
        orderRequest.setOrder_type("LIMIT");
        orderRequest.setTransaction_type(transactionType);
        orderRequest.setOrder_reference_id(generateOrderReferenceId());
        return orderRequest;
    }

    private String generateOrderReferenceId() {
        String raw = UUID.randomUUID().toString().replace("-", "");
        return raw.substring(0, 2) + "-" + raw.substring(2, 11) + "-" + raw.substring(11, 18);
    }

    private boolean isFno(String symbol) {
        if (symbol == null)
            return false;
        return symbol.endsWith("CE") || symbol.endsWith("PE") || symbol.matches(".*\\d+.*");
    }

    public Mono<Map<String, Double>> getLiveLTP(List<String> symbol) {
        // Implementation for fetching live LTP
        return clientHttp.getLiveLtp(symbol);
    }
}
