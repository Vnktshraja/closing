package com.vnkst.closing.cache;

import com.vnkst.closing.entity.DailyRecord;
import com.vnkst.closing.entity.FNOHighLow;
import com.vnkst.closing.entity.FourHoursCandles;
import com.vnkst.closing.entity.FourHoursRecord;
import com.vnkst.closing.entity.GrowwInstruments;
import com.vnkst.closing.entity.OneHourCandles;
import com.vnkst.closing.entity.OneHourRecord;
import com.vnkst.closing.entity.Orders;
import com.vnkst.closing.entity.PredictedStock;
import com.vnkst.closing.repository.FNOHighLowRepository;
import com.vnkst.closing.repository.FourHoursCandlesRepository;
import com.vnkst.closing.repository.FourHoursRecordRepository;
import com.vnkst.closing.repository.GrowwInstrumentRepository;
import com.vnkst.closing.repository.OneHourCandlesRepository;
import com.vnkst.closing.repository.OneHourRecordRepository;
import com.vnkst.closing.repository.OrdersRepository;
import com.vnkst.closing.repository.PredictedStockRepository;
import com.vnkst.closing.service.GrowwServer;
import com.vnkst.closing.service.OrderPlacingService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static com.vnkst.closing.constants.CommonConstants.DATE;
import static com.vnkst.closing.constants.CommonConstants.PRICE;
import static com.vnkst.closing.constants.CommonConstants.QUANTITY;
import static com.vnkst.closing.constants.CommonConstants.TIME;
import static com.vnkst.closing.utils.DatesUtil.getTodayDay;
import static com.vnkst.closing.utils.DatesUtil.getWorkingPreviousDay;
import static com.vnkst.closing.utils.DatesUtil.isWeekday;
import static com.vnkst.closing.utils.TempUtil.isItMarketTime;
import static com.vnkst.closing.utils.TempUtil.isMarketHours;

@Component
@RequiredArgsConstructor
@Setter
@Getter
@Slf4j
public class DataCache {

    private final OrdersRepository ordersRepository;
    private final GrowwInstrumentRepository growwInstrumentRepository;
    private final FNOHighLowRepository fnoHighLowRepository;
    private final OneHourRecordRepository oneHourRecordRepository;
    private final OneHourCandlesRepository oneHourCandlesRepository;
    private final FourHoursRecordRepository fourHoursRecordRepository;
    private final FourHoursCandlesRepository fourHoursCandlesRepository;
    private final PredictedStockRepository predictedStockRepository;

    private ConcurrentHashMap<String, Integer> lotSizeCache = new ConcurrentHashMap<>();
    private Set<String> nameCache = ConcurrentHashMap.newKeySet();
    private ConcurrentHashMap<String, Double> ltpCache = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, List<OneHourCandles>> latestAndLast5CandlesOf1HourCandles = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, List<FourHoursCandles>> latestAndLast2CandlesOf4HourCandles = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> oneHourVolumeCache = new ConcurrentHashMap<>();
    private final AtomicReference<LocalDate> fiveMinCacheDate = new AtomicReference<>(LocalDate.now());
    private final AtomicReference<LocalDate> tenMinCacheDate = new AtomicReference<>(LocalDate.now());

    // ── Migrated from LiveDataStore ───────────────────────────────────────────
    private final ConcurrentHashMap<String, Map<String, Object>> activeSymbols = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, String>> symbolsWithOptionTypeWithTradingSymbols = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> availableCash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> dayPnl = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> dailyPredictionCache = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, FNOHighLow> fnoHighAndLow = new ConcurrentHashMap<>();

    /**
     * Deduplication guard for the weekly breakout order strategy.
     */
    private final ConcurrentHashMap<String, Boolean> boughtWeeklySymbols = new ConcurrentHashMap<>();

    @Autowired
    @Lazy
    private GrowwServer growwServer;

    @Autowired
    @Lazy
    private OrderPlacingService orderPlacingService;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        loadLotSizeCache();
        loadActiveSymbols();
        previousDayPrediction();
        loadFnoHighAndLowCache();
        if (isItMarketTime(TIME) && isWeekday(DATE)) {
            loadLatestAndLast5CandlesOf1HourCandles();
            loadLatestAndLast2CandlesOf4HourCandles();
        }
    }

    private void previousDayPrediction() {
        log.info("Loading previous day prediction cache from database...");
        try {
            LocalDate previousDate = getWorkingPreviousDay();
            List<PredictedStock> stocks = predictedStockRepository.findByPredictionDate(previousDate);
            for (PredictedStock stock : stocks) {
                if (stock != null && stock.getSymbol() != null) {
                    String predictionType = (stock.getOptionType() != null && !stock.getOptionType().trim().isEmpty())
                            ? stock.getOptionType()
                            : stock.getAnalysisType();
                    if (predictionType != null) {
                        dailyPredictionCache.put(stock.getSymbol(), predictionType);
                    }
                }
            }
            log.info("Previous day prediction cache loaded: {} entries for date {}", dailyPredictionCache.size(), previousDate);
        } catch (Exception error) {
            log.error("Failed to load previous day prediction cache: ", error);
        }
    }

    private void loadActiveSymbols() {
        log.info("Loading active symbols from database...");
        try {
            List<Orders> openOrders = ordersRepository.getOpenOrder();
            for (Orders order : openOrders) {
                if (order != null && order.getSymbol() != null) {
                    Map<String, Object> valueMap = new HashMap<>();
                    valueMap.put(PRICE, order.getBoughtPrice());
                    valueMap.put(QUANTITY, order.getQuantity());
                    valueMap.put("order_type", order.getOrder_type());
                    activeSymbols.put(order.getSymbol(), valueMap);
                    log.info("Cached active symbol: {}, price: {}, quantity: {}, order_type: {}",
                            order.getSymbol(), order.getBoughtPrice(), order.getQuantity(), order.getOrder_type());
                }
            }
            log.info("Successfully loaded active symbols cache: {} entries", activeSymbols.size());
        } catch (Exception error) {
            log.error("Failed to load active symbols from database: ", error);
        }
    }

    public void loadLatestAndLast2CandlesOf4HourCandles() {
        Set<String> activeKeys = activeSymbols.keySet();
        log.info("Loading latest and last 2 candles of 4-hour candles from database for active symbols: {}",
                activeKeys);
        if (activeKeys.isEmpty()) {
            log.info("No active symbols found in activeSymbols cacheMap. Skipping loading 4-hour candles.");
            return;
        }
        try {
            List<FourHoursRecord> records = fourHoursRecordRepository.findBySymbolIn(activeKeys);
            Map<String, List<FourHoursCandles>> groupedBySymbol = new HashMap<>();
            for (FourHoursRecord record : records) {
                if (record != null && record.getSymbol() != null && record.getFourId() != null) {
                    List<FourHoursCandles> candles = fourHoursCandlesRepository.findByFourRecordId(record.getFourId());
                    groupedBySymbol.computeIfAbsent(record.getSymbol(), k -> new ArrayList<>()).addAll(candles);
                }
            }
            for (Map.Entry<String, List<FourHoursCandles>> entry : groupedBySymbol.entrySet()) {
                String symbol = entry.getKey();
                List<FourHoursCandles> candles = entry.getValue();
                if (symbol != null && candles != null && !candles.isEmpty()) {
                    candles.sort(Comparator.comparingInt(FourHoursCandles::getCount));
                    List<FourHoursCandles> last2 = candles.size() > 2
                            ? candles.subList(candles.size() - 2, candles.size())
                            : candles;
                    latestAndLast2CandlesOf4HourCandles.put(symbol, new CopyOnWriteArrayList<>(last2));
                    if (latestAndLast2CandlesOf4HourCandles.size() == 1) {
                        log.info("First 4-hour candles record cached: Symbol={}, Candles={}", symbol, last2);
                    }
                }
            }
            log.info("Loaded 4-hour candles cache: {} entries", latestAndLast2CandlesOf4HourCandles.size());
        } catch (Exception error) {
            log.error("Failed to load 4-hour candles cache: ", error);
        }
    }

    public void addLatest4HourCandle(String symbol, FourHoursCandles newCandle) {
        if (symbol == null || newCandle == null) {
            return;
        }
        List<FourHoursCandles> list = latestAndLast2CandlesOf4HourCandles.compute(symbol, (key, currentList) -> {
            if (currentList == null) {
                currentList = new CopyOnWriteArrayList<>();
            }
            while (currentList.size() >= 2) {
                currentList.remove(0);
            }
            currentList.add(newCandle);
            return currentList;
        });

        // ── Order Closing Exit Logic ──────────────────────────────────────────
        Map<String, Object> activeInfo = activeSymbols.get(symbol);
        if (activeInfo != null && !list.isEmpty() && isMarketHours()) {
            FNOHighLow fno = fnoHighAndLow.get(symbol);
            if (fno != null) {
                double boughtPrice = ((Number) activeInfo.get(PRICE)).doubleValue();
                double previousLow = fno.getPreviousLow();

                FourHoursCandles lastCandle = list.get(list.size() - 1);
                double closePrice = lastCandle.getClosePrice();

                if (closePrice < previousLow && closePrice < boughtPrice) {
                    log.warn("[OrderExit-4Hr] Triggering exit for symbol: {}. " +
                            "Reason: last candle close ({}) is below previousLow ({}) and boughtPrice ({}).",
                            symbol, closePrice, previousLow, boughtPrice);

                    String reason = String.format("4HourCandleCloseBelowSupport: Last 4-hr candle close (%.2f) below prevLow (%.2f) and boughtPrice (%.2f)",
                            closePrice, previousLow, boughtPrice);
                    try {
                        String status = orderPlacingService.sellOrders(symbol, closePrice, reason);
                        log.info("[OrderExit-4Hr] Sell order successfully submitted for {}. Status: {}", symbol, status);
                    } catch (Exception error) {
                        log.error("[OrderExit-4Hr] Error submitting sell order for {}: ", symbol, error);
                    }
                }
            }
        }
    }

    public void loadLatestAndLast5CandlesOf1HourCandles() {
        Set<String> activeKeys = activeSymbols.keySet();
        log.info("Loading latest and last 5 candles of 1-hour candles from database for active symbols: {}",
                activeKeys);
        if (activeKeys.isEmpty()) {
            log.info("No active symbols found in activeSymbols cacheMap. Skipping loading 1-hour candles.");
            return;
        }
        try {
            List<OneHourRecord> records = oneHourRecordRepository.findBySymbolIn(activeKeys);
            Map<String, List<OneHourCandles>> groupedBySymbol = new HashMap<>();
            for (OneHourRecord record : records) {
                if (record != null && record.getSymbol() != null && record.getOneId() != null) {
                    List<OneHourCandles> candles = oneHourCandlesRepository.findByOneRecordId(record.getOneId());
                    groupedBySymbol.computeIfAbsent(record.getSymbol(), k -> new ArrayList<>()).addAll(candles);
                }
            }
            for (Map.Entry<String, List<OneHourCandles>> entry : groupedBySymbol.entrySet()) {
                String symbol = entry.getKey();
                List<OneHourCandles> candles = entry.getValue();
                if (symbol != null && candles != null && !candles.isEmpty()) {
                    candles.sort(Comparator.comparingInt(OneHourCandles::getCount));
                    List<OneHourCandles> last5 = candles.size() > 5
                            ? candles.subList(candles.size() - 5, candles.size())
                            : candles;
                    latestAndLast5CandlesOf1HourCandles.put(symbol, new CopyOnWriteArrayList<>(last5));
                    if (latestAndLast5CandlesOf1HourCandles.size() == 1) {
                        log.info("First 1-hour candles record cached: Symbol={}, Candles={}", symbol, last5);
                    }
                }
            }
            log.info("Loaded 1-hour candles cache: {} entries", latestAndLast5CandlesOf1HourCandles.size());
        } catch (Exception error) {
            log.error("Failed to load 1-hour candles cache: ", error);
        }
    }

    public void addLatest1HourCandle(String symbol, OneHourCandles newCandle) {
        if (symbol == null || newCandle == null) {
            return;
        }
        List<OneHourCandles> list = latestAndLast5CandlesOf1HourCandles.compute(symbol, (key, currentList) -> {
            if (currentList == null) {
                currentList = new CopyOnWriteArrayList<>();
            }
            while (currentList.size() >= 5) {
                currentList.remove(0);
            }
            currentList.add(newCandle);
            return currentList;
        });

        // ── Order Closing Exit Logic ──────────────────────────────────────────
        Map<String, Object> activeInfo = activeSymbols.get(symbol);
        if (activeInfo != null && list.size() >= 3 && isMarketHours()) {
            FNOHighLow fno = fnoHighAndLow.get(symbol);
            if (fno != null) {
                double boughtPrice = ((Number) activeInfo.get(PRICE)).doubleValue();
                double previousLow = fno.getPreviousLow();

                int size = list.size();
                OneHourCandles c1 = list.get(size - 3);
                OneHourCandles c2 = list.get(size - 2);
                OneHourCandles c3 = list.get(size - 1);

                double close1 = c1.getClosePrice();
                double close2 = c2.getClosePrice();
                double close3 = c3.getClosePrice();

                if (close1 < previousLow && close2 < previousLow && close3 < previousLow &&
                        close1 < boughtPrice && close2 < boughtPrice && close3 < boughtPrice) {

                    log.warn("[OrderExit-1Hr] Triggering exit for symbol: {}. " +
                            "Reason: last 3 candle closes ({}, {}, {}) are below previousLow ({}) and boughtPrice ({}).",
                            symbol, close1, close2, close3, previousLow, boughtPrice);

                    String reason = String.format("1Hour3CandlesCloseBelowSupport: Last 3 1-hr candle closes (%.2f, %.2f, %.2f) below prevLow (%.2f) and boughtPrice (%.2f)",
                            close1, close2, close3, previousLow, boughtPrice);
                    try {
                        String status = orderPlacingService.sellOrders(symbol, close3, reason);
                        log.info("[OrderExit-1Hr] Sell order successfully submitted for {}. Status: {}", symbol, status);
                    } catch (Exception error) {
                        log.error("[OrderExit-1Hr] Error submitting sell order for {}: ", symbol, error);
                    }
                }
            }
        }
    }

    private void loadFnoHighAndLowCache() {
        log.info("Loading FNO high and low cache from database...");
        try {
            List<FNOHighLow> list = fnoHighLowRepository.findLatestBeforeDate(getWorkingPreviousDay());
            for (FNOHighLow highLow : list) {
                if (highLow != null && highLow.getTradingSymbol() != null) {
                    fnoHighAndLow.put(highLow.getTradingSymbol(), highLow);
                }
            }
            log.info("FNO high and low cache loaded: {} entries", fnoHighAndLow.size());
        } catch (Exception error) {
            log.error("Failed to load FNO high and low cache: ", error);
        }
    }

    private void loadLotSizeCache() {
        log.info("Loading lot size cache from database...");
        try {
            List<GrowwInstruments> instruments = growwInstrumentRepository.findAllSymbolAndLotSize();
            for (GrowwInstruments instrument : instruments) {
                if (instrument != null && instrument.getUnderlyingSymbol() != null) {
                    lotSizeCache.put(instrument.getUnderlyingSymbol(), instrument.getLotSize());
                }
            }
            log.info("Lot size cache loaded: {} entries", lotSizeCache.size());
        } catch (Exception error) {
            log.error("Failed to load lot size cache: ", error);
        }
    }

    public Set<String> getNames() {
        return nameCache;
    }

    public void updateTradingSymbols(List<String> tradingSymbols) {
        if (tradingSymbols == null || tradingSymbols.isEmpty()) {
            return;
        }
        nameCache.addAll(tradingSymbols);
    }

    public void updateFnoHighLow(FNOHighLow highLow) {
        if (highLow != null && highLow.getTradingSymbol() != null) {
            fnoHighAndLow.put(highLow.getTradingSymbol(), highLow);
        }
    }

    @Scheduled(cron = "0 0 17 * * *", zone = "Asia/Kolkata")
    public void clearCache() {
        nameCache.clear();
    }

    public void updateLtp(String symbol, double ltp) {
        ltpCache.put(symbol, ltp);
    }

    public void updateLtpCache(List<DailyRecord> records) {
        LocalDate day = getTodayDay();
        records.forEach(record -> {
            if (day.equals(record.getRecordDate())) {
                ltpCache.put(record.getSymbol(), record.getLtp());
            }
        });
    }

    public double getLtpBySymbol(String symbol) {
        return ltpCache.getOrDefault(symbol, 0.0);
    }

    public int getLotSizeBySymbol(String symbol) {
        if (lotSizeCache.containsKey(symbol)) {
            return lotSizeCache.get(symbol);
        }
        List<String> underlyings = new ArrayList<>(lotSizeCache.keySet());
        underlyings.sort((s1, s2) -> Integer.compare(s2.length(), s1.length()));
        for (String underlying : underlyings) {
            if (symbol.startsWith(underlying)) {
                return lotSizeCache.get(underlying);
            }
        }
        return 0;
    }

    public void saveAvailableCashToTrade(Double amount) {
        availableCash.put("CashAvailable", amount);
    }

    public Double getAvailableCashToTrade() {
        return availableCash.getOrDefault("CashAvailable", 0.0);
    }

    public void saveDayPnl(Double amount) {
        dayPnl.put("DayPnl", amount);
    }

    public Double getDayPnl() {
        return dayPnl.getOrDefault("DayPnl", 0.0);
    }

    public boolean alreadyExist(String symbol) {
        return activeSymbols.containsKey(symbol);
    }

    public void updateActiveSymbolPrice(String symbol, double price) {
        Map<String, Object> valueMap = activeSymbols.get(symbol);
        if (valueMap != null) {
            valueMap.put(PRICE, price);
        }
    }

    public void removeSymbols(String symbol) {
        activeSymbols.remove(symbol);
    }

    public void addActiveSymbols(String symbol, Map<String, Object> value) {
        if (!activeSymbols.containsKey(symbol)) {
            activeSymbols.put(symbol, value);
        }
    }

    public String getTradingSymbols(String symbol, String optionType) {
        Map<String, String> optionTypes = symbolsWithOptionTypeWithTradingSymbols.get(symbol);
        return optionTypes != null ? optionTypes.get(optionType) : null;
    }

    public void updateOneHourVolume(String symbol, long volume) {
        if (symbol != null) {
            oneHourVolumeCache.put(symbol, volume);
        }
    }

    public long getOneHourVolumeBySymbol(String symbol) {
        return symbol != null ? oneHourVolumeCache.getOrDefault(symbol, 0L) : 0L;
    }
}
