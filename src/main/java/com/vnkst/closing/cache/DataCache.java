package com.multiplier.reactive.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiplier.reactive.entity.*;
import com.multiplier.reactive.repository.*;
import com.multiplier.reactive.service.GrowwServer;
import com.multiplier.reactive.service.OrderPlacingService;
import com.multiplier.reactive.service.WebSocketBroadcastService;
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
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.multiplier.reactive.model.TenMinutesRecord;
import java.util.concurrent.atomic.AtomicReference;

import static com.multiplier.reactive.constants.CommonConstants.*;
import static com.multiplier.reactive.util.DatesUtil.*;
import static com.multiplier.reactive.util.TempUtil.isItMarketTime;
import static com.multiplier.reactive.util.TempUtil.isMarketHours;

@Component
@RequiredArgsConstructor
@Setter
@Getter
@Slf4j
public class DataCache {
    private final DailyRecordRepository dailyRecordRepository;
    private final OrdersRepository ordersRepository;
    private final GrowwInstrumentRepository growwInstrumentRepository;
    private final FNOHighLowRepository fnoHighLowRepository;
    private final OneHourRecordRepository oneHourRecordRepository;
    private final OneHourCandlesRepository oneHourCandlesRepository;
    private final FourHoursRecordRepository fourHoursRecordRepository;
    private final FourHoursCandlesRepository fourHoursCandlesRepository;
    private final PredictedStockRepository predictedStockRepository;
    private ConcurrentHashMap<String, Integer> lotSizeCache = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, OptionChainData> optionChainCache = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, FNOHighLow> fnoHighAndLow = new ConcurrentHashMap<>();

    private Set<String> nameCache = ConcurrentHashMap.newKeySet();
    private ConcurrentHashMap<String, Double> ltpCache = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, List<OneHourCandles>> latestAndLast5CandlesOf1HourCandles = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, List<FourHoursCandles>> latestAndLast2CandlesOf4HourCandles = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, FiveMinutesRecord> todayFiveMinutesRecordsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TenMinutesRecord> todayTenMinutesRecordsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> oneHourVolumeCache = new ConcurrentHashMap<>();
    private final AtomicReference<LocalDate> fiveMinCacheDate = new AtomicReference<>(LocalDate.now());
    private final AtomicReference<LocalDate> tenMinCacheDate = new AtomicReference<>(LocalDate.now());

    // ── Migrated from LiveDataStore ───────────────────────────────────────────
    private final ConcurrentHashMap<String, Map<String, Object>> activeSymbols = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, String>> symbolsWithOptionTypeWithTradingSymbols = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> availableCash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> dayPnl = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> dailyPredictionCache = new ConcurrentHashMap<>();

    /**
     * Deduplication guard for the weekly breakout order strategy.
     * Key = trading symbol; Value = true once an order has been placed for that
     * symbol
     * during the current session. Prevents the same symbol from being bought more
     * than
     * once by the crossed_weekly_high scheduler logic in TriggerApi.
     */
    private final ConcurrentHashMap<String, Boolean> boughtWeeklySymbols = new ConcurrentHashMap<>();

    @Autowired
    private WebSocketBroadcastService webSocketBroadcastService;
    private final ObjectMapper liveMapper = new ObjectMapper();

    @Autowired
    private OptionChainDataRepository optionChainRepository;

    @Autowired
    @Lazy
    private GrowwServer growwServer;

    @Autowired
    @Lazy
    private OrderPlacingService orderPlacingService;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        loadCache();
        loadCurrentDayRecord();
        loadLotSizeCache();
        loadActiveSymbols();
         previousDayPrediction();
        loadFnoHighAndLowCache();
        loadOptionChainCache().subscribe(null,
                error -> log.error("Failed to load option chain cache: ", error));
        loadSymbolsWithOptionTypeWithTradingSymbols().subscribe(null,
                error -> log.error("Failed to load symbols cache: ", error));
        if (isItMarketTime(TIME) && isWeekday(DATE)) {
            loadLatestAndLast5CandlesOf1HourCandles();
            loadLatestAndLast2CandlesOf4HourCandles();
        }
    }

    private void previousDayPrediction() {
        log.info("Loading previous day prediction cache from database...");
        LocalDate previousDate = getWorkingPreviousDay();
        predictedStockRepository.findByPredictionDate(previousDate)
                .filter(stock -> stock != null && stock.getSymbol() != null)
                .doOnNext(stock -> {
                    String predictionType = (stock.getOptionType() != null && !stock.getOptionType().trim().isEmpty())
                            ? stock.getOptionType()
                            : stock.getAnalysisType();
                    if (predictionType != null) {
                        dailyPredictionCache.put(stock.getSymbol(), predictionType);
                    }
                })
                .doOnComplete(() -> log.info("Previous day prediction cache loaded: {} entries for date {}", dailyPredictionCache.size(), previousDate))
                .subscribe(
                        null,
                        error -> log.error("Failed to load previous day prediction cache: ", error));
    }

    // Update activeSymbols cacheMap on startup: fetch only ACTIVE orders from
    // OrdersRepository and populate the map so downstream caches (1h/4h candles)
    // know which symbols to load. Uses .block() intentionally — this runs inside
    // @EventListener(ApplicationReadyEvent) on a non-reactive thread.
    private void loadActiveSymbols() {
        log.info("Loading active symbols from database...");
        try {
            ordersRepository.getOpenOrder()
                    .filter(order -> order != null && order.getSymbol() != null)
                    .doOnNext(order -> {
                        Map<String, Object> valueMap = new java.util.HashMap<>();
                        valueMap.put(PRICE, order.getBoughtPrice());
                        valueMap.put(QUANTITY, order.getQuantity());
                        valueMap.put("order_type", order.getOrder_type());
                        activeSymbols.put(order.getSymbol(), valueMap);
                        log.info("Cached active symbol: {}, price: {}, quantity: {}, order_type: {}",
                                order.getSymbol(), order.getBoughtPrice(), order.getQuantity(), order.getOrder_type());
                    })
                    .collectList()
                    .block();
            log.info("Successfully loaded active symbols cache: {} entries", activeSymbols.size());
        } catch (Exception error) {
            log.error("Failed to load active symbols from database: ", error);
        }
    }

    // fetch last 2 candles from FourHoursCandlesRepository, use
    // FourHoursRecordRepository for symbol, key is symbol and value is candle with
    // insertion order. handles proper null checks.
    public void loadLatestAndLast2CandlesOf4HourCandles() {
        Set<String> activeKeys = activeSymbols.keySet();
        log.info("Loading latest and last 2 candles of 4-hour candles from database for active symbols: {}",
                activeKeys);
        if (activeKeys.isEmpty()) {
            log.info("No active symbols found in activeSymbols cacheMap. Skipping loading 4-hour candles.");
            return;
        }
        fourHoursRecordRepository.findBySymbolIn(activeKeys)
                .filter(record -> record != null && record.getSymbol() != null && record.getFourId() != null)
                .flatMap(record -> fourHoursCandlesRepository.findByFourRecordId(record.getFourId())
                        .map(candle -> Tuples.of(record.getSymbol(), candle)))
                .groupBy(tuple -> tuple.getT1())
                .flatMap(groupedFlux -> groupedFlux
                        .map(tuple -> tuple.getT2())
                        .collectList()
                        .map(candles -> {
                            candles.sort(java.util.Comparator.comparingInt(FourHoursCandles::getCount));
                            List<FourHoursCandles> last2 = candles.size() > 2
                                    ? candles.subList(candles.size() - 2, candles.size())
                                    : candles;
                            return Tuples.of(groupedFlux.key(), last2);
                        }))
                .subscribe(
                        tuple -> {
                            String symbol = tuple.getT1();
                            List<FourHoursCandles> list = tuple.getT2();
                            if (symbol != null && list != null && !list.isEmpty()) {
                                latestAndLast2CandlesOf4HourCandles.put(
                                        symbol,
                                        new CopyOnWriteArrayList<>(list));
                                if (latestAndLast2CandlesOf4HourCandles.size() == 1) {
                                    log.info("First 4-hour candles record cached: Symbol={}, Candles={}", symbol, list);
                                }
                            }
                        },
                        error -> log.error("Failed to load 4-hour candles cache: ", error),
                        () -> log.info("Loaded 4-hour candles cache: {} entries",
                                latestAndLast2CandlesOf4HourCandles.size()));
    }

    /**
     * Appends a new 4-hour candle to the cache for the specified symbol.
     * Ensures the cached list does not exceed 2 elements by removing the oldest
     * candle if the capacity limit is reached.
     *
     * @param symbol    ticker symbol
     * @param newCandle new candle data to cache
     */
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
        // Check if the symbol currently has an active open order position
        Map<String, Object> activeInfo = activeSymbols.get(symbol);
        if (activeInfo != null && !list.isEmpty() && isMarketHours()) {
            FNOHighLow fno = fnoHighAndLow.get(symbol);
            if (fno != null) {
                double boughtPrice = ((Number) activeInfo.get(PRICE)).doubleValue();
                double previousLow = fno.getPreviousLow();

                FourHoursCandles lastCandle = list.get(list.size() - 1);
                double closePrice = lastCandle.getClosePrice();

                // Exit condition: last 4-hour candle closes below previousLow and below
                // boughtPrice
                if (closePrice < previousLow && closePrice < boughtPrice) {

                    log.warn("[OrderExit-4Hr] Triggering exit for symbol: {}. " +
                            "Reason: last candle close ({}) is below previousLow ({}) and boughtPrice ({}).",
                            symbol, closePrice, previousLow, boughtPrice);

                    String reason = String.format("4HourCandleCloseBelowSupport: Last 4-hr candle close (%.2f) below prevLow (%.2f) and boughtPrice (%.2f)",
                            closePrice, previousLow, boughtPrice);
                    // Call the sellOrders API asynchronously
                    orderPlacingService.sellOrders(symbol, closePrice, reason)
                            .subscribe(
                                    status -> log.info(
                                            "[OrderExit-4Hr] Sell order successfully submitted for {}. Status: {}",
                                            symbol, status),
                                    error -> log.error("[OrderExit-4Hr] Error submitting sell order for {}: ", symbol,
                                            error));
                }
            }
        }
    }

    // fetch last 5 candles from OneHourCandlesRepository, use
    // OneHourRecordRepository for symbol, key is symbol and value is candle with
    // insertion order. handles proper null checks.
    public void loadLatestAndLast5CandlesOf1HourCandles() {
        Set<String> activeKeys = activeSymbols.keySet();
        log.info("Loading latest and last 5 candles of 1-hour candles from database for active symbols: {}",
                activeKeys);
        if (activeKeys.isEmpty()) {
            log.info("No active symbols found in activeSymbols cacheMap. Skipping loading 1-hour candles.");
            return;
        }
        oneHourRecordRepository.findBySymbolIn(activeKeys)
                .filter(record -> record != null && record.getSymbol() != null && record.getOneId() != null)
                .flatMap(record -> oneHourCandlesRepository.findByOneRecordId(record.getOneId())
                        .map(candle -> Tuples.of(record.getSymbol(), candle)))
                .groupBy(tuple -> tuple.getT1())
                .flatMap(groupedFlux -> groupedFlux
                        .map(tuple -> tuple.getT2())
                        .collectList()
                        .map(candles -> {
                            candles.sort(java.util.Comparator.comparingInt(OneHourCandles::getCount));
                            List<OneHourCandles> last5 = candles.size() > 5
                                    ? candles.subList(candles.size() - 5, candles.size())
                                    : candles;
                            return Tuples.of(groupedFlux.key(), last5);
                        }))
                .subscribe(
                        tuple -> {
                            String symbol = tuple.getT1();
                            List<OneHourCandles> list = tuple.getT2();
                            if (symbol != null && list != null && !list.isEmpty()) {
                                latestAndLast5CandlesOf1HourCandles.put(
                                        symbol,
                                        new CopyOnWriteArrayList<>(list));
                                if (latestAndLast5CandlesOf1HourCandles.size() == 1) {
                                    log.info("First 1-hour candles record cached: Symbol={}, Candles={}", symbol, list);
                                }
                            }
                        },
                        error -> log.error("Failed to load 1-hour candles cache: ", error),
                        () -> log.info("Loaded 1-hour candles cache: {} entries",
                                latestAndLast5CandlesOf1HourCandles.size()));
    }

    /**
     * Appends a new 1-hour candle to the cache for the specified symbol.
     * Ensures the cached list does not exceed 5 elements by removing the oldest
     * candle if the capacity limit is reached.
     *
     * @param symbol    ticker symbol
     * @param newCandle new candle data to cache
     */
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
        // Check if the symbol currently has an active open order position
        Map<String, Object> activeInfo = activeSymbols.get(symbol);
        if (activeInfo != null && list.size() >= 3 && isMarketHours()) {
            FNOHighLow fno = fnoHighAndLow.get(symbol);
            if (fno != null) {
                double boughtPrice = ((Number) activeInfo.get(PRICE)).doubleValue();
                double previousLow = fno.getPreviousLow();

                int size = list.size();
                OneHourCandles c1 = list.get(size - 3);
                OneHourCandles c2 = list.get(size - 2);
                OneHourCandles c3 = list.get(size - 1); // Latest candle

                double close1 = c1.getClosePrice();
                double close2 = c2.getClosePrice();
                double close3 = c3.getClosePrice();

                // Exit condition: all 3 most recent 1-hour candles must close below previousLow
                // and below boughtPrice
                if (close1 < previousLow && close2 < previousLow && close3 < previousLow &&
                        close1 < boughtPrice && close2 < boughtPrice && close3 < boughtPrice) {

                    log.warn("[OrderExit-1Hr] Triggering exit for symbol: {}. " +
                            "Reason: last 3 candle closes ({}, {}, {}) are below previousLow ({}) and boughtPrice ({}).",
                            symbol, close1, close2, close3, previousLow, boughtPrice);

                    String reason = String.format("1Hour3CandlesCloseBelowSupport: Last 3 1-hr candle closes (%.2f, %.2f, %.2f) below prevLow (%.2f) and boughtPrice (%.2f)",
                            close1, close2, close3, previousLow, boughtPrice);
                    // Call the sellOrders API asynchronously
                    orderPlacingService.sellOrders(symbol, close3, reason)
                            .subscribe(
                                    status -> log.info(
                                            "[OrderExit-1Hr] Sell order successfully submitted for {}. Status: {}",
                                            symbol, status),
                                    error -> log.error("[OrderExit-1Hr] Error submitting sell order for {}: ", symbol,
                                            error));
                }
            }
        }
    }

    private void loadFnoHighAndLowCache() {
        log.info("Loading FNO high and low cache from database...");
        fnoHighLowRepository.findLatestBeforeDate(getWorkingPreviousDay())
                .doOnNext(highLow -> fnoHighAndLow.put(highLow.getTradingSymbol(), highLow))
                .doOnComplete(() -> log.info("FNO high and low cache loaded: {} entries", fnoHighAndLow.size()))
                .subscribe(
                        null,
                        error -> log.error("Failed to load FNO high and low cache: ", error));
    }

    private void loadLotSizeCache() {
        log.info("Loading lot size cache from database...");
        growwInstrumentRepository.findAllSymbolAndLotSize()
                .doOnNext(instrument -> lotSizeCache.put(instrument.getUnderlyingSymbol(), instrument.getLotSize()))
                .doOnComplete(() -> log.info("Lot size cache loaded: {} entries", lotSizeCache.size()))
                .subscribe(
                        null,
                        error -> log.error("Failed to load lot size cache: ", error));
    }

    public void loadCache() {
        log.info("Loading trading symbols from database...");
        optionChainRepository.findAllTradingSymbol()
                .doOnNext(nameCache::add)
                .subscribe(
                        null,
                        error -> log.error("Failed to load trading symbols cache: ", error));
    }

    public Set<String> getNames() {
        return nameCache;
    }

    public Mono<Void> updateTradingSymbols(List<String> tradingSymbols) {
        if (tradingSymbols == null || tradingSymbols.isEmpty()) {
            return Mono.empty();
        }
        nameCache.addAll(tradingSymbols);
        return Mono.empty();
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

    public void loadCurrentDayRecord() {
        LocalDate day = getTodayDay();
        log.info("Loading current day LTPs from database for date: {}", day);
        dailyRecordRepository.findBySymbolAndLtp(day)
                .doOnNext(entry -> ltpCache.put(entry.getSymbol(), entry.getLtp()))
                .subscribe(
                        null,
                        error -> log.error("Failed to load current day LTP cache: ", error));
    }

    public Mono<Void> updateLtp(String symbol, double ltp) {
        ltpCache.put(symbol, ltp);
        return Mono.empty();
    }

    public Mono<Void> updateLtpCache(List<DailyRecord> records) {
        LocalDate day = getTodayDay();
        records.forEach(record -> {
            if (day.equals(record.getRecordDate())) {
                ltpCache.put(record.getSymbol(), record.getLtp());
            }
        });
        return Mono.empty();
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

    // ── LiveDataStore methods ─────────────────────────────────────────────────

    public void saveAvailableCashToTrade(Double amount) {
        availableCash.put("CashAvailable", amount);
        broadcast();
    }

    public Double getAvailableCashToTrade() {
        return availableCash.getOrDefault("CashAvailable", 0.0);
    }

    public void saveDayPnl(Double amount) {
        dayPnl.put("DayPnl", amount);
        broadcast();
    }

    public Double getDayPnl() {
        return dayPnl.getOrDefault("DayPnl", 0.0);
    }

    private void broadcast() {
        if (webSocketBroadcastService != null) {
            try {
                Double balance = getAvailableCashToTrade();
                Double pnl = getDayPnl();
                ObjectNode node = liveMapper.createObjectNode();
                node.put("balance", balance != null ? balance : 0.0);
                node.put("dayPnl", pnl != null ? pnl : 0.0);
                webSocketBroadcastService.publish("portfolio-stats", node.toString());
            } catch (Exception e) {
                log.error("Error broadcasting stats", e);
            }
        }
    }

    public void addActiveSymbols(String symbol, Map<String, Object> value) {
        if (!activeSymbols.containsKey(symbol)) {
            activeSymbols.put(symbol, value);
        }
    }

    public void removeSymbols(String symbol) {
        activeSymbols.remove(symbol);
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

    public Mono<Void> loadOptionChainCache() {
        Mono<Double> cashMono;
        Double cachedCash = getAvailableCashToTrade();
        if (cachedCash != null && cachedCash > 0.0) {
            cashMono = Mono.just(cachedCash);
        } else {
            cashMono = growwServer != null
                    ? growwServer.getOnlyBalance().doOnNext(this::saveAvailableCashToTrade).defaultIfEmpty(0.0)
                    : Mono.just(0.0);
        }

        return cashMono.flatMap(cash -> {
            optionChainCache.clear();
            return optionChainRepository.findAll()
                    .filter(data -> {
                        Integer lotSize = data.getLotSize();
                        Double ltp = data.getLtp();
                        if (lotSize == null || ltp == null) {
                            return false;
                        }
                        return (lotSize * ltp) < cash;
                    })
                    .doOnNext(data -> {
                        if (data.getTradingSymbol() != null) {
                            optionChainCache.put(data.getTradingSymbol(), data);
                        }
                        if (data.getSymbol() != null && data.getOptionType() != null && data.getTradingSymbol() != null) {
                            symbolsWithOptionTypeWithTradingSymbols
                                    .computeIfAbsent(data.getSymbol(), k -> new ConcurrentHashMap<>())
                                    .put(data.getOptionType(), data.getTradingSymbol());
                        }
                    })
                    .then();
        });
    }

    public ConcurrentHashMap<String, OptionChainData> getOptionChainCache() {
        return optionChainCache;
    }

    public Mono<Void> loadSymbolsWithOptionTypeWithTradingSymbols() {
        symbolsWithOptionTypeWithTradingSymbols.clear();
        return optionChainRepository.findAll()
                .doOnNext(data -> {
                    if (data.getSymbol() != null && data.getOptionType() != null && data.getTradingSymbol() != null) {
                        symbolsWithOptionTypeWithTradingSymbols
                                .computeIfAbsent(data.getSymbol(), k -> new ConcurrentHashMap<>())
                                .put(data.getOptionType(), data.getTradingSymbol());
                    }
                })
                .then();
    }

    // use this method within checkAndSavePredictions to save tradingSymbol in symbol on predicted stocks
    public String getTradingSymbols(String symbol, String optionType){
        Map<String, String> optionTypes = symbolsWithOptionTypeWithTradingSymbols.get(symbol);
        return optionTypes != null ? optionTypes.get(optionType) : null;
    }

    public Mono<Void> checkAndResetFiveMinutesCache() {
        return Mono.defer(() -> {
            LocalDate today = LocalDate.now();
            LocalDate cached = fiveMinCacheDate.get();
            if (!today.equals(cached)) {
                todayFiveMinutesRecordsCache.clear();
                fiveMinCacheDate.set(today);
                log.info("[FiveMinutesCache] Date changed from {} to {}. Cleared cache.", cached, today);
            }
            return Mono.empty();
        });
    }

    public Mono<Void> checkAndResetTenMinutesCache() {
        return Mono.defer(() -> {
            LocalDate today = LocalDate.now();
            LocalDate cached = tenMinCacheDate.get();
            if (!today.equals(cached)) {
                todayTenMinutesRecordsCache.clear();
                tenMinCacheDate.set(today);
                log.info("[TenMinutesCache] Date changed from {} to {}. Cleared cache.", cached, today);
            }
            return Mono.empty();
        });
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
