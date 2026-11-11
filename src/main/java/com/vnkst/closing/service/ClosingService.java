package com.vnkst.closing.service;

import com.vnkst.closing.cache.DataCache;
import com.vnkst.closing.entity.FNOHighLow;
import com.vnkst.closing.entity.Orders;
import com.vnkst.closing.repository.OrdersRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.vnkst.closing.constants.CommonConstants.ACTIVE;
import static com.vnkst.closing.constants.CommonConstants.MANUAL;
import static com.vnkst.closing.constants.CommonConstants.PRICE;
import static com.vnkst.closing.constants.CommonConstants.QUANTITY;
import static com.vnkst.closing.utils.TempUtil.isMarketHours;

@Service
@Slf4j
public class ClosingService {

    @Autowired
    private DataCache dataCache;

    @Autowired
    private OrderPlacingService orderPlacingService;

    @Autowired
    private OrdersRepository ordersRepository;

    @Autowired
    private OneHourService oneHourService;

    @Autowired
    private FourHoursService fourHoursService;

    private final Set<String> processedManualOrderSymbols = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean isManual4HourSyncRunning = new AtomicBoolean(false);

    /**
     * Scheduled job running every 1 minute during market hours to monitor active
     * orders for exit conditions.
     * Evaluates live Last Traded Price (LTP) against active positions to trigger
     * auto-sell orders under:
     * 1. Max Loss Threshold: Position P&L falls to or below -₹500 (MAX_LOSS_500).
     * 2. Technical Stop Loss: Live price drops below the previous day's close
     * (STOP_LOSS).
     * 3. Take Profit: Live price reaches or exceeds the monthly high (TAKE_PROFIT).
     * Automatically triggers sell orders via orderPlacingService and removes exited
     * symbols from dataCache.
     */
    @Scheduled(fixedRate = 60 * 1000, zone = "Asia/Kolkata") // Every 1 minute
    public void checkActiveOrdersForLoss() {
        if (!isMarketHours()) {
            return;
        }
        try {
            Map<String, Map<String, Object>> activeSymbols = dataCache.getActiveSymbols();
            if (activeSymbols == null || activeSymbols.isEmpty()) {
                return;
            }

            // Evict symbols that are no longer active from processed manual orders tracker
            processedManualOrderSymbols.removeIf(sym -> !activeSymbols.containsKey(sym));
            List<String> symbolsToCheck = new ArrayList<>(activeSymbols.keySet());

            List<String> newManualSymbols = findNewManualOrderSymbols(activeSymbols);
            if (!newManualSymbols.isEmpty() && isManual4HourSyncRunning.compareAndSet(false, true)) {
                try {
                    log.info(
                            "[Loss-Check] First time manual order found for symbol(s): {}. Starting 7-day 1-hour & 30-day 4-hour candle track and FNO high/low update...",
                            newManualSymbols);
                    run30Days4HourCandlesAndFnoHighLow(newManualSymbols);
                    processedManualOrderSymbols.addAll(newManualSymbols);
                    log.info(
                            "[Loss-Check] Successfully completed candle track and FNO high/low update for manual symbols: {}",
                            newManualSymbols);
                } catch (Exception err) {
                    log.error(
                            "[Loss-Check] Error running candle track for manual orders: {}",
                            newManualSymbols, err);
                } finally {
                    isManual4HourSyncRunning.set(false);
                }
            }

            Map<String, FNOHighLow> fnoMap = dataCache.getFnoHighAndLow();
            Map<String, Double> ltpMap = orderPlacingService.getLiveLTP(symbolsToCheck);

            double totalLoss = 0.0;
            List<String> symbolsSummary = new ArrayList<>();

            for (String symbol : symbolsToCheck) {
                Map<String, Object> activeInfo = activeSymbols.get(symbol);
                if (activeInfo == null) {
                    continue;
                }

                double livePrice = resolveLivePrice(ltpMap, symbol);
                Object priceObj = activeInfo.get(PRICE);
                Object qtyObj = activeInfo.get(QUANTITY);
                double boughtPrice = priceObj instanceof Number
                        ? ((Number) priceObj).doubleValue()
                        : 0.0;
                int quantity = qtyObj instanceof Number ? ((Number) qtyObj).intValue() : 0;

                if (livePrice <= 0.0) {
                    log.warn(
                            "[Loss-Check] Cannot check loss for {}: Live price unavailable (livePrice={}). Skipping exit check.",
                            symbol, livePrice);
                    symbolsSummary.add(String.format("%s (LTP Unavailable, Bought: %.2f, Qty: %d)",
                            symbol, boughtPrice, quantity));
                    continue;
                }

                boolean shouldExit = false;
                String exitType = "";
                double currentPnl = 0.0;
                double currentLoss = 0.0;

                if (boughtPrice > 0 && quantity > 0) {
                    currentPnl = (livePrice - boughtPrice) * quantity;
                    currentLoss = currentPnl < 0 ? Math.abs(currentPnl) : 0.0;
                    totalLoss += currentLoss;
                    symbolsSummary.add(String.format(
                            "%s (Loss: ₹%.2f, PnL: ₹%.2f, Live: %.2f, Bought: %.2f, Qty: %d)",
                            symbol, currentLoss, currentPnl, livePrice, boughtPrice, quantity));
                    log.info(
                            "[Loss-Check] Symbol: {}, Live Price: {}, Bought Price: {}, Qty: {}, Loss: ₹{}, PnL: ₹{}",
                            symbol, livePrice, boughtPrice, quantity,
                            String.format("%.2f", currentLoss),
                            String.format("%.2f", currentPnl));

                    // 1. Check Max Loss (-500)
                    if (currentPnl <= -500.0) {
                        shouldExit = true;
                        exitType = String.format(
                                "MAX_LOSS_500: PnL is ₹%.2f <= -500 (Loss ₹%.2f, LivePrice %.2f, BoughtPrice %.2f, Qty %d)",
                                currentPnl, currentLoss, livePrice, boughtPrice, quantity);
                        log.warn(
                                "[Loss-Check] Max loss threshold reached (Loss: ₹{} >= 500, PnL: ₹{} <= -500)! Triggering exit for symbol: {}",
                                String.format("%.2f", currentLoss),
                                String.format("%.2f", currentPnl), symbol);
                    }
                } else {
                    symbolsSummary.add(String.format("%s (Live: %.2f, Missing Price/Qty)",
                            symbol, livePrice));
                }

                // 2. Check Technical Stop Loss / Take Profit if not already triggered by Max Loss
                if (!shouldExit && fnoMap != null) {
                    FNOHighLow prev = fnoMap.get(symbol);
                    if (prev == null) {
                        String cleanSymbol = symbol.replaceAll("^(NSE|BSE)[_:]", "");
                        prev = fnoMap.get(cleanSymbol);
                    }
                    if (prev == null) {
                        prev = fnoMap.get("NSE_" + symbol);
                    }
                    if (prev != null) {
                        double prevClose = prev.getPreviousClose();
                        double monthlyHigh = prev.getMonthlyHigh();

                        log.info(
                                "[Loss-Check] Symbol: {}, Live Price: {}, Previous Close: {}, Monthly High: {}",
                                symbol, livePrice, prevClose, monthlyHigh);

                        if (prevClose > 0 && livePrice < prevClose) {
                            shouldExit = true;
                            exitType = String.format(
                                    "STOP_LOSS: Live price %.2f dropped below previous close %.2f",
                                    livePrice, prevClose);
                            log.warn(
                                    "[Loss-Check] Price dropped below previous close! Triggering exit for symbol: {}, livePrice: {}, prevClose: {}",
                                    symbol, livePrice, prevClose);
                        } else if (monthlyHigh > 0 && livePrice >= monthlyHigh) {
                            shouldExit = true;
                            exitType = String.format(
                                    "TAKE_PROFIT: Live price %.2f reached or crossed monthly high %.2f",
                                    livePrice, monthlyHigh);
                            log.info(
                                    "[Loss-Check] Price hit/crossed monthly high! Triggering exit for symbol: {}, livePrice: {}, monthlyHigh: {}",
                                    symbol, livePrice, monthlyHigh);
                        }
                    }
                }

                if (shouldExit) {
                    try {
                        String status = orderPlacingService.sellOrders(symbol, livePrice, exitType);
                        log.info("[Loss-Check] Exit order status for {} ({}): {}", symbol, exitType, status);
                        dataCache.removeSymbols(symbol);
                    } catch (Exception err) {
                        log.error("[Loss-Check] Failed to exit order for {} ({})", symbol, exitType, err);
                    }
                }
            }

            log.info(
                    "[Loss-Check] Checking {} active symbols for potential loss exit: {} | Total Loss: ₹{}",
                    symbolsToCheck.size(), symbolsSummary, String.format("%.2f", totalLoss));
            log.info("[Loss-Check] Finished checking active orders for loss");
        } catch (Exception error) {
            log.error("[Loss-Check] Error checking active orders for loss", error);
        }
    }

    /**
     * Executes 1-hour candles processing for 7 days (-7 days to currentday -1)
     * and 4-hour candles processing for 30 days (-30 days to currentday -1)
     * in a separate flow only for newly placed active orders,
     * and updates fno_high_low table and dataCache (weeklyHigh, monthlyHigh, prevClose, etc.).
     */
    public void run30Days4HourCandlesAndFnoHighLow(List<String> manualSymbols) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate endDate = today.minusDays(1);
        String endDateTime = endDate + " 15:30:00";
        String encodedEnd = URLEncoder.encode(endDateTime, StandardCharsets.UTF_8);

        // 7 days for 1-hour candles (weekly high / low / prev close)
        LocalDate startDate7 = today.minusDays(7);
        String startDateTime7 = startDate7 + " 09:15:00";
        String encodedStart7 = URLEncoder.encode(startDateTime7, StandardCharsets.UTF_8);

        // 30 days for 4-hour candles (monthly high / low)
        LocalDate startDate30 = today.minusDays(30);
        String startDateTime30 = startDate30 + " 09:15:00";
        String encodedStart30 = URLEncoder.encode(startDateTime30, StandardCharsets.UTF_8);

        log.info("[ManualOrder-CandleTrack] Running 7-day 1-hour and 30-day 4-hour candles for manual orders: {}",
                manualSymbols);

        try {
            oneHourService.process1HoursForSymbols(manualSymbols, encodedStart7, encodedEnd);
            fourHoursService.process4HoursForSymbols(manualSymbols, encodedStart30, encodedEnd);
            oneHourService.calculateAndSaveFnoHighLowForSymbols(manualSymbols, today);
            dataCache.loadLatestAndLast2CandlesOf4HourCandles();
            log.info(
                    "[ManualOrder-CandleTrack] Successfully saved 7-day 1-hr & 30-day 4-hr candles and updated fno_high_low & cache for active manual symbols: {}",
                    manualSymbols);
        } catch (Exception err) {
            log.error(
                    "[ManualOrder-CandleTrack] Failed to process candle history or update FNO high/low for active manual symbols: {}",
                    manualSymbols, err);
        }
    }

    private List<String> findNewManualOrderSymbols(Map<String, Map<String, Object>> activeSymbols) {
        List<String> manualSymbols = new ArrayList<>();
        List<String> unknownSymbols = new ArrayList<>();

        for (Map.Entry<String, Map<String, Object>> entry : activeSymbols.entrySet()) {
            String symbol = entry.getKey();
            if (processedManualOrderSymbols.contains(symbol)) {
                continue;
            }
            Map<String, Object> info = entry.getValue();
            Object typeObj = info != null
                    ? (info.get("order_type") != null ? info.get("order_type") : info.get("orderType"))
                    : null;
            if (typeObj != null) {
                if (MANUAL.equalsIgnoreCase(typeObj.toString())) {
                    manualSymbols.add(symbol);
                }
            } else {
                unknownSymbols.add(symbol);
            }
        }

        if (unknownSymbols.isEmpty()) {
            return manualSymbols;
        }

        List<Orders> activeOrders = ordersRepository.getOrdersByState(ACTIVE);
        for (Orders order : activeOrders) {
            if (order.getSymbol() != null && unknownSymbols.contains(order.getSymbol())) {
                Map<String, Object> info = activeSymbols.get(order.getSymbol());
                if (info != null && order.getOrder_type() != null) {
                    info.put("order_type", order.getOrder_type());
                }
                if (MANUAL.equalsIgnoreCase(order.getOrder_type())
                        && !processedManualOrderSymbols.contains(order.getSymbol())) {
                    manualSymbols.add(order.getSymbol());
                }
            }
        }

        return new ArrayList<>(new LinkedHashSet<>(manualSymbols));
    }

    private double resolveLivePrice(Map<String, Double> ltpMap, String symbol) {
        if (ltpMap != null && !ltpMap.isEmpty()) {
            if (ltpMap.containsKey(symbol) && ltpMap.get(symbol) != null && ltpMap.get(symbol) > 0) {
                return ltpMap.get(symbol);
            }
            String nseSymbol = "NSE_" + symbol;
            if (ltpMap.containsKey(nseSymbol) && ltpMap.get(nseSymbol) != null && ltpMap.get(nseSymbol) > 0) {
                return ltpMap.get(nseSymbol);
            }
            // Case-insensitive & prefix-stripped lookup
            for (Map.Entry<String, Double> entry : ltpMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                    String cleanKey = entry.getKey().replaceAll("^(NSE|BSE)[_:]", "");
                    if (entry.getKey().equalsIgnoreCase(symbol) || cleanKey.equalsIgnoreCase(symbol)) {
                        return entry.getValue();
                    }
                }
            }
        }
        double cached = dataCache.getLtpBySymbol(symbol);
        return cached > 0 ? cached : 0.0;
    }
}
