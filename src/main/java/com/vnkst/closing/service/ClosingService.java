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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.vnkst.closing.constants.CommonConstants.ACTIVE;
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

    private final Set<String> processedOrderSymbols = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean isCandleSyncRunning = new AtomicBoolean(false);

    /**
     * Scheduled job running every 1 minute during market hours to monitor active
     * orders for exit conditions.
     *
     * When active orders are found for the first time (or missing from FNOHighLow cache),
     * it fetches 7-day 1-hour candles and 30-day 4-hour candles, calculates and saves
     * FNOHighLow (previousClose, weeklyHigh, weeklyLow, monthlyHigh, monthlyLow) to DB and DataCache.
     *
     * Evaluates live Last Traded Price (LTP) against active positions to trigger
     * auto-sell orders under:
     * 1. Max Loss Threshold: Position P&L falls to or below -₹500 (MAX_LOSS_500).
     * 2. Technical Stop Loss: Live price drops below previous day's close, weekly low, or monthly low.
     * 3. Take Profit: Live price reaches or exceeds monthly high or weekly high.
     */
    @Scheduled(fixedRate = 60 * 1000, zone = "Asia/Kolkata") // Every 1 minute
    public void checkActiveOrdersForLoss() {
        if (!isMarketHours()) {
            return;
        }
        try {
            Map<String, Map<String, Object>> activeSymbols = dataCache.getActiveSymbols();
            if (activeSymbols == null || activeSymbols.isEmpty()) {
                syncActiveOrdersFromDbToCache();
                activeSymbols = dataCache.getActiveSymbols();
                if (activeSymbols == null || activeSymbols.isEmpty()) {
                    return;
                }
            }

            // Evict symbols that are no longer active from processed orders tracker
            processedOrderSymbols.removeIf(sym -> !dataCache.getActiveSymbols().containsKey(sym));
            List<String> symbolsToCheck = new ArrayList<>(activeSymbols.keySet());

            List<String> newOrderSymbols = findNewOrderSymbols(activeSymbols);
            if (!newOrderSymbols.isEmpty() && isCandleSyncRunning.compareAndSet(false, true)) {
                try {
                    log.info(
                            "[Loss-Check] First time active order(s) found for symbol(s): {}. Starting 7-day 1-hour & 30-day 4-hour candle track and FNO high/low update...",
                            newOrderSymbols);
                    run30Days4HourCandlesAndFnoHighLow(newOrderSymbols);
                    processedOrderSymbols.addAll(newOrderSymbols);
                    log.info(
                            "[Loss-Check] Successfully completed candle track and FNO high/low update for symbols: {}",
                            newOrderSymbols);
                } catch (Exception err) {
                    log.error(
                            "[Loss-Check] Error running candle track for first-time orders: {}",
                            newOrderSymbols, err);
                } finally {
                    isCandleSyncRunning.set(false);
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

                // 2. Check Technical Stop Loss / Take Profit using FNOHighLow (prevClose, weeklyHigh/Low, monthlyHigh/Low)
                if (!shouldExit && fnoMap != null) {
                    FNOHighLow prev = resolveFnoHighLow(fnoMap, symbol);
                    if (prev != null) {
                        double prevClose = prev.getPreviousClose();
                        double weeklyHigh = prev.getWeeklyHigh();
                        double weeklyLow = prev.getWeeklyLow();
                        double monthlyHigh = prev.getMonthlyHigh();
                        double monthlyLow = prev.getMonthlyLow();

                        log.info(
                                "[Loss-Check] Symbol: {}, Live Price: {}, Prev Close: {}, Weekly Low: {}, Weekly High: {}, Monthly Low: {}, Monthly High: {}",
                                symbol, livePrice, prevClose, weeklyLow, weeklyHigh, monthlyLow, monthlyHigh);

                        if (prevClose > 0 && livePrice < prevClose) {
                            shouldExit = true;
                            exitType = String.format(
                                    "STOP_LOSS: Live price %.2f dropped below previous close %.2f",
                                    livePrice, prevClose);
                            log.warn(
                                    "[Loss-Check] Price dropped below previous close! Triggering exit for symbol: {}, livePrice: {}, prevClose: {}",
                                    symbol, livePrice, prevClose);
                        } else if (weeklyLow > 0 && livePrice <= weeklyLow) {
                            shouldExit = true;
                            exitType = String.format(
                                    "STOP_LOSS_WEEKLY_LOW: Live price %.2f dropped to/below weekly low %.2f",
                                    livePrice, weeklyLow);
                            log.warn(
                                    "[Loss-Check] Price dropped to/below weekly low! Triggering exit for symbol: {}, livePrice: {}, weeklyLow: {}",
                                    symbol, livePrice, weeklyLow);
                        } else if (monthlyLow > 0 && livePrice <= monthlyLow) {
                            shouldExit = true;
                            exitType = String.format(
                                    "STOP_LOSS_MONTHLY_LOW: Live price %.2f dropped to/below monthly low %.2f",
                                    livePrice, monthlyLow);
                            log.warn(
                                    "[Loss-Check] Price dropped to/below monthly low! Triggering exit for symbol: {}, livePrice: {}, monthlyLow: {}",
                                    symbol, livePrice, monthlyLow);
                        } else if (monthlyHigh > 0 && livePrice >= monthlyHigh) {
                            shouldExit = true;
                            exitType = String.format(
                                    "TAKE_PROFIT: Live price %.2f reached or crossed monthly high %.2f",
                                    livePrice, monthlyHigh);
                            log.info(
                                    "[Loss-Check] Price hit/crossed monthly high! Triggering exit for symbol: {}, livePrice: {}, monthlyHigh: {}",
                                    symbol, livePrice, monthlyHigh);
                        } else if (weeklyHigh > 0 && livePrice >= weeklyHigh) {
                            shouldExit = true;
                            exitType = String.format(
                                    "TAKE_PROFIT_WEEKLY_HIGH: Live price %.2f reached or crossed weekly high %.2f",
                                    livePrice, weeklyHigh);
                            log.info(
                                    "[Loss-Check] Price hit/crossed weekly high! Triggering exit for symbol: {}, livePrice: {}, weeklyHigh: {}",
                                    symbol, livePrice, weeklyHigh);
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
     * for newly detected active orders, and updates fno_high_low table and dataCache
     * (weeklyHigh, weeklyLow, monthlyHigh, monthlyLow, prevClose, etc.).
     */
    public void run30Days4HourCandlesAndFnoHighLow(List<String> symbols) {
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

        log.info("[Order-CandleTrack] Running 7-day 1-hour and 30-day 4-hour candles for active orders: {}",
                symbols);

        try {
            oneHourService.process1HoursForSymbols(symbols, encodedStart7, encodedEnd);
            fourHoursService.process4HoursForSymbols(symbols, encodedStart30, encodedEnd);
            oneHourService.calculateAndSaveFnoHighLowForSymbols(symbols, today);
            dataCache.loadLatestAndLast5CandlesOf1HourCandles();
            dataCache.loadLatestAndLast2CandlesOf4HourCandles();
            log.info(
                    "[Order-CandleTrack] Successfully saved 7-day 1-hr & 30-day 4-hr candles and updated fno_high_low & cache for active symbols: {}",
                    symbols);
        } catch (Exception err) {
            log.error(
                    "[Order-CandleTrack] Failed to process candle history or update FNO high/low for active symbols: {}",
                    symbols, err);
        }
    }

    /**
     * Finds any active order symbols that are encountered for the first time
     * (whether AUTO or MANUAL) or are missing from the FNOHighLow cache.
     */
    private List<String> findNewOrderSymbols(Map<String, Map<String, Object>> activeSymbols) {
        Set<String> newSymbols = new LinkedHashSet<>();
        Map<String, FNOHighLow> fnoMap = dataCache.getFnoHighAndLow();

        // Sync any active orders from DB that might not be in activeSymbols yet
        List<Orders> activeOrders = ordersRepository.getOrdersByState(ACTIVE);
        for (Orders order : activeOrders) {
            if (order != null && order.getSymbol() != null) {
                String sym = order.getSymbol();
                Map<String, Object> info = activeSymbols.get(sym);
                if (info == null) {
                    info = new HashMap<>();
                    info.put(PRICE, order.getBoughtPrice());
                    info.put(QUANTITY, order.getQuantity());
                    info.put("order_type", order.getOrder_type());
                    dataCache.addActiveSymbols(sym, info);
                } else if (info.get("order_type") == null && order.getOrder_type() != null) {
                    info.put("order_type", order.getOrder_type());
                }
            }
        }

        for (String symbol : dataCache.getActiveSymbols().keySet()) {
            boolean missingFnoStats = (fnoMap == null || resolveFnoHighLow(fnoMap, symbol) == null);
            if (!processedOrderSymbols.contains(symbol) || missingFnoStats) {
                newSymbols.add(symbol);
            }
        }

        return new ArrayList<>(newSymbols);
    }

    private void syncActiveOrdersFromDbToCache() {
        try {
            List<Orders> activeOrders = ordersRepository.getOrdersByState(ACTIVE);
            for (Orders order : activeOrders) {
                if (order != null && order.getSymbol() != null) {
                    Map<String, Object> valueMap = new HashMap<>();
                    valueMap.put(PRICE, order.getBoughtPrice());
                    valueMap.put(QUANTITY, order.getQuantity());
                    valueMap.put("order_type", order.getOrder_type());
                    dataCache.addActiveSymbols(order.getSymbol(), valueMap);
                }
            }
        } catch (Exception e) {
            log.error("[Loss-Check] Failed to sync active orders from DB to cache", e);
        }
    }

    private FNOHighLow resolveFnoHighLow(Map<String, FNOHighLow> fnoMap, String symbol) {
        if (fnoMap == null || symbol == null) {
            return null;
        }
        FNOHighLow prev = fnoMap.get(symbol);
        if (prev == null) {
            String cleanSymbol = symbol.replaceAll("^(NSE|BSE)[_:]", "");
            prev = fnoMap.get(cleanSymbol);
        }
        if (prev == null) {
            prev = fnoMap.get("NSE_" + symbol);
        }
        return prev;
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
