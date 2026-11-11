package com.vnkst.closing.service;

import com.vnkst.closing.cache.DataCache;
import com.vnkst.closing.entity.FNOHighLow;
import com.vnkst.closing.entity.FourHoursCandles;
import com.vnkst.closing.entity.FourHoursRecord;
import com.vnkst.closing.entity.OneHourCandles;
import com.vnkst.closing.entity.OneHourIndexCandles;
import com.vnkst.closing.entity.OneHourIndexRecord;
import com.vnkst.closing.entity.OneHourRecord;
import com.vnkst.closing.entity.SymbolUrl;
import com.vnkst.closing.repository.FNOHighLowRepository;
import com.vnkst.closing.repository.FourHoursCandlesRepository;
import com.vnkst.closing.repository.FourHoursRecordRepository;
import com.vnkst.closing.repository.OneHourCandlesRepository;
import com.vnkst.closing.repository.OneHourIndexCandlesRepository;
import com.vnkst.closing.repository.OneHourIndexRecordRepository;
import com.vnkst.closing.repository.OneHourRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.vnkst.closing.constants.CommonConstants.REQUESTS_PER_SECOND;

@Service
@RequiredArgsConstructor
@Slf4j
public class OneHourService {

    private final GrowwServer growwServer;
    private final DailyUrlProvider dailyUrlProvider;
    private final OneHourRecordRepository oneHourRecordRepository;
    private final OneHourCandlesRepository oneHourCandlesRepository;
    private final OneHourIndexRecordRepository oneHourIndexRecordRepository;
    private final OneHourIndexCandlesRepository oneHourIndexCandlesRepository;
    private final FourHoursRecordRepository fourHoursRecordRepository;
    private final FourHoursCandlesRepository fourHoursCandlesRepository;
    private final FNOHighLowRepository fnoHighLowRepository;
    private final DataCache dataCache;
    private final ObjectMapper mapper;

    public void truncateTable() {
        oneHourRecordRepository.truncateTable();
    }

    public List<OneHourRecord> process1Hours(String startTime, String endTime) {
        return process1Hours(startTime, endTime, true);
    }

    public List<OneHourIndexRecord> processIndex1Hours(String startTime, String endTime) {
        return processIndex1Hours(startTime, endTime, true);
    }

    public List<OneHourRecord> process1Hours(String startTime, String endTime, boolean saveToDb) {
        String decodedEnd = URLDecoder.decode(endTime, StandardCharsets.UTF_8);
        LocalDate enteredDate = LocalDate.parse(decodedEnd.substring(0, 10));
        return saveOneHourDaily(dailyUrlProvider.getDaily1HoursUrls(startTime, endTime), enteredDate, saveToDb);
    }

    public List<OneHourIndexRecord> processIndex1Hours(String startTime, String endTime, boolean saveToDb) {
        String decodedEnd = URLDecoder.decode(endTime, StandardCharsets.UTF_8);
        LocalDate enteredDate = LocalDate.parse(decodedEnd.substring(0, 10));
        return saveIndexDaily(dailyUrlProvider.getDailyIndex1HoursUrls(startTime, endTime), enteredDate, saveToDb);
    }

    public List<OneHourRecord> process1HoursForSymbols(Collection<String> symbols, String startTime, String endTime) {
        String decodedEnd = URLDecoder.decode(endTime, StandardCharsets.UTF_8);
        LocalDate enteredDate = LocalDate.parse(decodedEnd.substring(0, 10));
        return saveOneHourDailyForSymbols(symbols, dailyUrlProvider.getDaily1HoursUrls(symbols, startTime, endTime), enteredDate);
    }

    @Transactional
    public List<OneHourRecord> saveOneHourDailyForSymbols(Collection<String> symbols, List<SymbolUrl> symbolUrls, LocalDate enteredDate) {
        try {
            List<OneHourRecord> records = new ArrayList<>();
            long delayMillis = (long) (1000.0 / REQUESTS_PER_SECOND);

            for (SymbolUrl req : symbolUrls) {
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                String response = growwServer.callApi2(req.url());
                OneHourRecord record = convertToEntity(req.symbol(), response, enteredDate);
                if (record != null) {
                    record.setOneId(null);
                    records.add(record);
                }
            }

            if (records.isEmpty()) {
                return new ArrayList<>();
            }

            if (symbols != null && !symbols.isEmpty()) {
                oneHourRecordRepository.deleteBySymbolIn(symbols);
            }

            List<OneHourRecord> savedRecords = oneHourRecordRepository.saveAll(records);
            Map<String, Long> keyToIdMap = new HashMap<>();
            for (OneHourRecord saved : savedRecords) {
                String key = saved.getSymbol() + "_" + saved.getStartTime();
                keyToIdMap.put(key, saved.getOneId());
            }

            List<OneHourCandles> allCandles = new ArrayList<>();
            for (OneHourRecord originalRecord : records) {
                String key = originalRecord.getSymbol() + "_" + originalRecord.getStartTime();
                Long parentId = keyToIdMap.get(key);
                if (parentId != null) {
                    originalRecord.setOneId(parentId);
                    boolean isActive = dataCache.alreadyExist(originalRecord.getSymbol());
                    if (originalRecord.getCandles() != null) {
                        int count = 1;
                        for (OneHourCandles candle : originalRecord.getCandles()) {
                            candle.setOneCandleId(null);
                            candle.setOneRecordId(parentId);
                            candle.setCount(count++);
                            allCandles.add(candle);
                            dataCache.updateOneHourVolume(originalRecord.getSymbol(), candle.getVolume());
                            if (isActive) {
                                dataCache.addLatest1HourCandle(originalRecord.getSymbol(), candle);
                            }
                        }
                    }
                }
            }

            if (!allCandles.isEmpty()) {
                for (int i = 0; i < allCandles.size(); i += 5000) {
                    List<OneHourCandles> batch = allCandles.subList(i, Math.min(i + 5000, allCandles.size()));
                    oneHourCandlesRepository.saveAll(batch);
                }
            }

            return savedRecords;
        } catch (Exception e) {
            log.error("Error saving 1-hour records for symbols {}: {}", symbols, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @Transactional
    public List<OneHourRecord> saveOneHourDaily(List<SymbolUrl> symbolUrls, LocalDate enteredDate, boolean saveToDb) {
        try {
            List<OneHourRecord> records = new ArrayList<>();
            long delayMillis = (long) (1000.0 / REQUESTS_PER_SECOND);

            for (SymbolUrl req : symbolUrls) {
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                String response = growwServer.callApi2(req.url());
                OneHourRecord record = convertToEntity(req.symbol(), response, enteredDate);
                if (record != null) {
                    record.setOneId(null);
                    records.add(record);
                }
            }

            if (!saveToDb || records.isEmpty()) {
                return records;
            }

            oneHourRecordRepository.truncateTable();
            List<OneHourRecord> savedRecords = oneHourRecordRepository.saveAll(records);

            Map<String, Long> keyToIdMap = new HashMap<>();
            for (OneHourRecord saved : savedRecords) {
                String key = saved.getSymbol() + "_" + saved.getStartTime();
                keyToIdMap.put(key, saved.getOneId());
            }

            List<OneHourCandles> allCandles = new ArrayList<>();
            for (OneHourRecord originalRecord : records) {
                String key = originalRecord.getSymbol() + "_" + originalRecord.getStartTime();
                Long parentId = keyToIdMap.get(key);
                if (parentId != null) {
                    originalRecord.setOneId(parentId);
                    boolean isActive = dataCache.alreadyExist(originalRecord.getSymbol());
                    if (originalRecord.getCandles() != null) {
                        int count = 1;
                        for (OneHourCandles candle : originalRecord.getCandles()) {
                            candle.setOneCandleId(null);
                            candle.setOneRecordId(parentId);
                            candle.setCount(count++);
                            allCandles.add(candle);
                            dataCache.updateOneHourVolume(originalRecord.getSymbol(), candle.getVolume());
                            if (isActive) {
                                dataCache.addLatest1HourCandle(originalRecord.getSymbol(), candle);
                            }
                        }
                    }
                }
            }

            if (!allCandles.isEmpty()) {
                for (int i = 0; i < allCandles.size(); i += 5000) {
                    List<OneHourCandles> batch = allCandles.subList(i, Math.min(i + 5000, allCandles.size()));
                    oneHourCandlesRepository.saveAll(batch);
                }
            }

            return savedRecords;
        } catch (Exception e) {
            log.error("Error due to : {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @Transactional
    public List<OneHourIndexRecord> saveIndexDaily(List<SymbolUrl> symbolUrls, LocalDate enteredDate, boolean saveToDb) {
        try {
            List<OneHourIndexRecord> records = new ArrayList<>();
            long delayMillis = (long) (1000.0 / REQUESTS_PER_SECOND);

            for (SymbolUrl req : symbolUrls) {
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                String response = growwServer.callApi2(req.url());
                OneHourIndexRecord record = convertToIndexEntity(req.symbol(), response, enteredDate);
                if (record != null) {
                    record.setOneId(null);
                    records.add(record);
                }
            }

            if (!saveToDb || records.isEmpty()) {
                return records;
            }

            oneHourIndexRecordRepository.truncateTable();
            List<OneHourIndexRecord> savedRecords = oneHourIndexRecordRepository.saveAll(records);

            Map<String, Long> keyToIdMap = new HashMap<>();
            for (OneHourIndexRecord saved : savedRecords) {
                String key = saved.getSymbol() + "_" + saved.getStartTime();
                keyToIdMap.put(key, saved.getOneId());
            }

            List<OneHourIndexCandles> allCandles = new ArrayList<>();
            for (OneHourIndexRecord originalRecord : records) {
                String key = originalRecord.getSymbol() + "_" + originalRecord.getStartTime();
                Long parentId = keyToIdMap.get(key);
                if (parentId != null) {
                    originalRecord.setOneId(parentId);
                    if (originalRecord.getCandles() != null) {
                        int count = 1;
                        for (OneHourIndexCandles candle : originalRecord.getCandles()) {
                            candle.setOneCandleId(null);
                            candle.setOneRecordId(parentId);
                            candle.setCount(count++);
                            allCandles.add(candle);
                            dataCache.updateOneHourVolume(originalRecord.getSymbol(), candle.getVolume());
                        }
                    }
                }
            }

            if (!allCandles.isEmpty()) {
                for (int i = 0; i < allCandles.size(); i += 5000) {
                    List<OneHourIndexCandles> batch = allCandles.subList(i, Math.min(i + 5000, allCandles.size()));
                    oneHourIndexCandlesRepository.saveAll(batch);
                }
            }

            return savedRecords;
        } catch (Exception e) {
            log.error("Error in saveIndexDaily: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private OneHourIndexRecord convertToIndexEntity(String symbol, String response, LocalDate enteredDate) {
        if (response == null || response.startsWith("FAIL") || response.startsWith("ERROR")) {
            log.warn("Invalid response received for symbol: {}. Response: {}", symbol, response);
            return null;
        }
        try {
            JsonNode root = mapper.readTree(response);
            JsonNode payload = root.path("payload");
            if (payload.isMissingNode()) {
                return null;
            }

            OneHourIndexRecord record = new OneHourIndexRecord();
            record.setSymbol(symbol);
            record.setStartTime(formatRawTimeToHHmm(payload.path("start_time").asText()));
            record.setEndTime(formatRawTimeToHHmm(payload.path("end_time").asText()));
            record.setEnteredDate(enteredDate);
            record.setUpdatedTime(LocalTime.now());
            record.setIntervalInMinutes(payload.path("interval_in_minutes").asInt());
            JsonNode candlesFromGrow = payload.path("candles");
            List<OneHourIndexCandles> candlesList = new ArrayList<>();
            if (candlesFromGrow.isArray()) {
                int candleCount = 1;
                for (JsonNode candleNode : candlesFromGrow) {
                    if (candleNode.isArray() && candleNode.size() >= 6) {
                        OneHourIndexCandles candle = new OneHourIndexCandles();

                        long epoch = candleNode.get(0).asLong();
                        Instant instant = epoch > 9999999999L ? Instant.ofEpochMilli(epoch)
                                : Instant.ofEpochSecond(epoch);
                        String readableTime = instant.atZone(ZoneId.of("Asia/Kolkata"))
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                        candle.setTimestamp(readableTime);

                        double open = candleNode.get(1).asDouble();
                        double high = candleNode.get(2).asDouble();
                        double low = candleNode.get(3).asDouble();
                        double close = candleNode.get(4).asDouble();
                        long volume = candleNode.get(5).asLong();

                        candle.setOpenPrice(open);
                        candle.setHighPrice(high);
                        candle.setLowPrice(low);
                        candle.setClosePrice(close);
                        candle.setVolume(volume);
                        candle.setCount(candleCount++);

                        String colour = close >= open ? "GREEN" : "RED";
                        candle.setColour(colour);
                        candle.setTrend(close >= open ? "UP" : "DOWN");

                        candlesList.add(candle);
                    }
                }
            }
            record.setCandles(candlesList);

            if (!candlesList.isEmpty()) {
                double firstOpen = candlesList.get(0).getOpenPrice();
                double lastClose = candlesList.get(candlesList.size() - 1).getClosePrice();
                record.setTrend(lastClose >= firstOpen ? "UP" : "DOWN");
            } else {
                record.setTrend("SIDEWAYS");
            }

            return record;
        } catch (Exception exception) {
            log.error("Exception occurred while converting response to index entity for symbol: " + symbol, exception);
        }
        return null;
    }

    private OneHourRecord convertToEntity(String symbol, String response, LocalDate enteredDate) {
        if (response == null || response.startsWith("FAIL") || response.startsWith("ERROR")) {
            log.warn("Invalid response received for symbol: {}. Response: {}", symbol, response);
            return null;
        }
        try {
            JsonNode root = mapper.readTree(response);
            JsonNode payload = root.path("payload");
            if (payload.isMissingNode()) {
                return null;
            }

            OneHourRecord record = new OneHourRecord();
            record.setSymbol(symbol);
            record.setStartTime(formatRawTimeToHHmm(payload.path("start_time").asText()));
            record.setEndTime(formatRawTimeToHHmm(payload.path("end_time").asText()));
            record.setEnteredDate(enteredDate);
            record.setUpdatedTime(LocalTime.now());
            record.setIntervalInMinutes(payload.path("interval_in_minutes").asInt());
            JsonNode candlesFromGrow = payload.path("candles");
            List<OneHourCandles> candlesList = new ArrayList<>();
            if (candlesFromGrow.isArray()) {
                int candleCount = 1;
                for (JsonNode candleNode : candlesFromGrow) {
                    if (candleNode.isArray() && candleNode.size() >= 6) {
                        OneHourCandles candle = new OneHourCandles();

                        long epoch = candleNode.get(0).asLong();
                        Instant instant = epoch > 9999999999L ? Instant.ofEpochMilli(epoch)
                                : Instant.ofEpochSecond(epoch);
                        String readableTime = instant.atZone(ZoneId.of("Asia/Kolkata"))
                                .format(DateTimeFormatter.ofPattern("HH:mm"));
                        candle.setTimestamp(readableTime);

                        double open = candleNode.get(1).asDouble();
                        double high = candleNode.get(2).asDouble();
                        double low = candleNode.get(3).asDouble();
                        double close = candleNode.get(4).asDouble();
                        long volume = candleNode.get(5).asLong();

                        candle.setOpenPrice(open);
                        candle.setHighPrice(high);
                        candle.setLowPrice(low);
                        candle.setClosePrice(close);
                        candle.setVolume(volume);
                        candle.setCount(candleCount++);

                        String colour = close >= open ? "GREEN" : "RED";
                        candle.setColour(colour);
                        candle.setTrend(close >= open ? "UP" : "DOWN");

                        candlesList.add(candle);
                    }
                }
            }
            record.setCandles(candlesList);

            if (!candlesList.isEmpty()) {
                double firstOpen = candlesList.get(0).getOpenPrice();
                double lastClose = candlesList.get(candlesList.size() - 1).getClosePrice();
                record.setTrend(lastClose >= firstOpen ? "UP" : "DOWN");
            } else {
                record.setTrend("SIDEWAYS");
            }

            return record;
        } catch (Exception exception) {
            log.error("Exception occurred while converting response to entity for symbol: " + symbol, exception);
        }
        return null;
    }

    private String formatRawTimeToHHmm(String rawTime) {
        if (rawTime == null || rawTime.isEmpty()) {
            return "";
        }
        try {
            if (rawTime.matches("\\d+")) {
                long epoch = Long.parseLong(rawTime);
                Instant instant = epoch > 9999999999L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
                return instant.atZone(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("HH:mm"));
            }
            LocalDateTime dateTime = LocalDateTime.parse(rawTime, DateTimeFormatter.ISO_DATE_TIME);
            return dateTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception e) {
            log.error("Error formatting raw time: " + rawTime, e);
            return rawTime;
        }
    }

    @Transactional
    public void calculateAndSaveFnoHighLow(LocalDate date) {
        LocalDate today = (date != null) ? date : LocalDate.now(ZoneId.of("Asia/Kolkata"));
        log.debug("[FNOHighLow] Starting calculation of FNO high and low for date: {}", today);

        try {
            List<OneHourRecord> oneHourRecords = oneHourRecordRepository.findAllBeforeOrEqualDate(today);
            List<FourHoursRecord> fourHoursRecords = fourHoursRecordRepository.findAllBeforeOrEqualDate(today);
            log.debug("[FNOHighLow] Retrieved {} 1-hour records and {} 4-hour records from DB.",
                    oneHourRecords.size(), fourHoursRecords.size());

            Set<String> targetSymbolSet = new LinkedHashSet<>();
            for (OneHourRecord r : oneHourRecords) {
                if (r.getSymbol() != null && !r.getSymbol().trim().isEmpty()) {
                    targetSymbolSet.add(r.getSymbol());
                }
            }
            for (FourHoursRecord r : fourHoursRecords) {
                if (r.getSymbol() != null && !r.getSymbol().trim().isEmpty()) {
                    targetSymbolSet.add(r.getSymbol());
                }
            }
            List<String> targetSymbols = new ArrayList<>(targetSymbolSet);

            if (targetSymbols.isEmpty()) {
                log.warn("[FNOHighLow] No target symbols found in 1-hour or 4-hour records. Skipping FNO high/low calculation.");
                return;
            }

            List<FNOHighLow> aggregatesToSave = buildFnoHighLowList(targetSymbols, oneHourRecords, fourHoursRecords, today);
            if (aggregatesToSave.isEmpty()) {
                log.info("[FNOHighLow] No aggregates to save.");
                return;
            }

            log.debug("[FNOHighLow] Truncating fno_high_low table and saving {} records.", aggregatesToSave.size());
            fnoHighLowRepository.truncateTable();
            List<FNOHighLow> saved = fnoHighLowRepository.saveAll(aggregatesToSave);
            saved.forEach(dataCache::updateFnoHighLow);
        } catch (Exception error) {
            log.error("[FNOHighLow] Error calculating and saving FNO High and Low: {}", error.getMessage(), error);
        }
    }

    @Transactional
    public void calculateAndSaveFnoHighLowForSymbols(Collection<String> symbols, LocalDate date) {
        if (symbols == null || symbols.isEmpty()) {
            return;
        }
        LocalDate today = (date != null) ? date : LocalDate.now(ZoneId.of("Asia/Kolkata"));
        log.info("[FNOHighLow] Starting targeted FNO high and low calculation for symbols: {} on date: {}", symbols, today);

        try {
            List<OneHourRecord> oneHourRecords = oneHourRecordRepository.findBySymbolIn(symbols);
            List<FourHoursRecord> fourHoursRecords = fourHoursRecordRepository.findBySymbolIn(symbols);

            List<FNOHighLow> aggregatesToSave = buildFnoHighLowList(symbols, oneHourRecords, fourHoursRecords, today);
            if (aggregatesToSave.isEmpty()) {
                return;
            }

            fnoHighLowRepository.deleteByTradingSymbolIn(symbols);
            List<FNOHighLow> saved = fnoHighLowRepository.saveAll(aggregatesToSave);
            saved.forEach(dataCache::updateFnoHighLow);
        } catch (Exception error) {
            log.error("[FNOHighLow] Error calculating targeted FNO High and Low for symbols {}: {}",
                    symbols, error.getMessage(), error);
        }
    }

    private List<FNOHighLow> buildFnoHighLowList(Collection<String> symbols, List<OneHourRecord> oneHourRecords,
            List<FourHoursRecord> fourHoursRecords, LocalDate today) {
        Set<Long> oneRecordIds = new HashSet<>();
        Map<String, Long> symbolToTodayRecordId = new HashMap<>();
        Map<String, Long> symbolToLastRecordId = new HashMap<>();
        Map<String, List<Long>> symbolToWeeklyRecordIds = new HashMap<>();

        for (String symbol : symbols) {
            List<OneHourRecord> sorted = oneHourRecords.stream()
                    .filter(r -> symbol.equals(r.getSymbol()))
                    .sorted(Comparator.comparing(OneHourRecord::getEnteredDate).reversed())
                    .collect(Collectors.toList());

            if (sorted.isEmpty()) {
                continue;
            }

            symbolToTodayRecordId.put(symbol, sorted.get(0).getOneId());
            if (sorted.size() > 1) {
                symbolToLastRecordId.put(symbol, sorted.get(1).getOneId());
            }

            List<Long> weeklyIds = sorted.stream()
                    .limit(7)
                    .map(OneHourRecord::getOneId)
                    .collect(Collectors.toList());
            symbolToWeeklyRecordIds.put(symbol, weeklyIds);
            oneRecordIds.addAll(weeklyIds);
        }

        Map<String, List<Long>> symbolToFourRecordIds = new HashMap<>();
        Set<Long> fourRecordIds = new HashSet<>();

        for (String symbol : symbols) {
            List<FourHoursRecord> sortedFour = fourHoursRecords.stream()
                    .filter(r -> symbol.equals(r.getSymbol()))
                    .sorted(Comparator.comparing(FourHoursRecord::getEnteredDate).reversed())
                    .collect(Collectors.toList());

            List<Long> limitFourIds = sortedFour.stream()
                    .limit(29)
                    .map(FourHoursRecord::getFourId)
                    .collect(Collectors.toList());

            symbolToFourRecordIds.put(symbol, limitFourIds);
            fourRecordIds.addAll(limitFourIds);
        }

        List<OneHourCandles> oneHourCandles = new ArrayList<>();
        if (!oneRecordIds.isEmpty()) {
            for (List<Long> part : partitionList(new ArrayList<>(oneRecordIds), 500)) {
                oneHourCandles.addAll(oneHourCandlesRepository.findByOneRecordIdsIn(part));
            }
        }

        List<FourHoursCandles> fourHoursCandles = new ArrayList<>();
        if (!fourRecordIds.isEmpty()) {
            for (List<Long> part : partitionList(new ArrayList<>(fourRecordIds), 500)) {
                fourHoursCandles.addAll(fourHoursCandlesRepository.findByFourRecordIdsIn(part));
            }
        }

        Map<Long, List<OneHourCandles>> oneCandlesByRecordId = oneHourCandles.stream()
                .collect(Collectors.groupingBy(OneHourCandles::getOneRecordId));

        Map<Long, List<FourHoursCandles>> fourCandlesByRecordId = fourCandlesByRecord(fourHoursCandles);

        List<FNOHighLow> aggregatesToSave = new ArrayList<>();

        for (String symbol : symbols) {
            double prevOpen = 0.0;
            double prevClose = 0.0;
            double prevHigh = 0.0;
            double prevLow = 0.0;

            Long todayRecordId = symbolToTodayRecordId.get(symbol);
            if (todayRecordId != null) {
                List<OneHourCandles> allCandles = oneCandlesByRecordId.getOrDefault(todayRecordId, new ArrayList<>());
                if (!allCandles.isEmpty()) {
                    List<OneHourCandles> sortedAll = allCandles.stream()
                            .sorted(Comparator.comparing(OneHourCandles::getOneCandleId))
                            .collect(Collectors.toList());

                    List<List<OneHourCandles>> dayGroups = new ArrayList<>();
                    List<OneHourCandles> currentDay = new ArrayList<>();
                    for (OneHourCandles c : sortedAll) {
                        if ("09:15".equals(c.getTimestamp()) && !currentDay.isEmpty()) {
                            dayGroups.add(currentDay);
                            currentDay = new ArrayList<>();
                        }
                        currentDay.add(c);
                    }
                    if (!currentDay.isEmpty()) {
                        dayGroups.add(currentDay);
                    }

                    if (!dayGroups.isEmpty()) {
                        List<OneHourCandles> prevDayCandles = dayGroups.get(dayGroups.size() - 1);
                        List<OneHourCandles> sortedPrev = prevDayCandles.stream()
                                .sorted(Comparator.comparing(OneHourCandles::getTimestamp))
                                .collect(Collectors.toList());
                        prevOpen = sortedPrev.get(0).getOpenPrice();
                        prevClose = sortedPrev.get(sortedPrev.size() - 1).getClosePrice();
                        prevHigh = prevDayCandles.stream().mapToDouble(OneHourCandles::getHighPrice).max().orElse(0.0);
                        prevLow = prevDayCandles.stream().mapToDouble(OneHourCandles::getLowPrice).min().orElse(0.0);
                    }
                }
            }

            if (prevOpen == 0.0 && prevClose == 0.0) {
                Long lastRecordId = symbolToLastRecordId.get(symbol);
                if (lastRecordId != null) {
                    List<OneHourCandles> lastCandles = oneCandlesByRecordId.getOrDefault(lastRecordId, new ArrayList<>());
                    if (!lastCandles.isEmpty()) {
                        List<OneHourCandles> sortedLast = lastCandles.stream()
                                .sorted(Comparator.comparing(OneHourCandles::getOneCandleId))
                                .collect(Collectors.toList());
                        List<List<OneHourCandles>> dayGroups2 = new ArrayList<>();
                        List<OneHourCandles> currDay2 = new ArrayList<>();
                        for (OneHourCandles c : sortedLast) {
                            if ("09:15".equals(c.getTimestamp()) && !currDay2.isEmpty()) {
                                dayGroups2.add(currDay2);
                                currDay2 = new ArrayList<>();
                            }
                            currDay2.add(c);
                        }
                        if (!currDay2.isEmpty()) dayGroups2.add(currDay2);
                        if (!dayGroups2.isEmpty()) {
                            List<OneHourCandles> lastDayCandles = dayGroups2.get(dayGroups2.size() - 1);
                            List<OneHourCandles> sortedLastDay = lastDayCandles.stream()
                                    .sorted(Comparator.comparing(OneHourCandles::getTimestamp))
                                    .collect(Collectors.toList());
                            prevOpen = sortedLastDay.get(0).getOpenPrice();
                            prevClose = sortedLastDay.get(sortedLastDay.size() - 1).getClosePrice();
                            prevHigh = lastDayCandles.stream().mapToDouble(OneHourCandles::getHighPrice).max().orElse(0.0);
                            prevLow = lastDayCandles.stream().mapToDouble(OneHourCandles::getLowPrice).min().orElse(0.0);
                        }
                    }
                }
            }

            double weeklyHigh = 0.0;
            double weeklyLow = 0.0;
            List<Long> weeklyIds = symbolToWeeklyRecordIds.getOrDefault(symbol, new ArrayList<>());
            List<OneHourCandles> weeklyCandles = new ArrayList<>();
            for (Long wid : weeklyIds) {
                weeklyCandles.addAll(oneCandlesByRecordId.getOrDefault(wid, new ArrayList<>()));
            }
            if (!weeklyCandles.isEmpty()) {
                weeklyHigh = weeklyCandles.stream().mapToDouble(OneHourCandles::getHighPrice).max().orElse(0.0);
                weeklyLow = weeklyCandles.stream().mapToDouble(OneHourCandles::getLowPrice).min().orElse(0.0);
            }

            double monthlyHigh = 0.0;
            double monthlyLow = 0.0;
            List<Long> fourIds = symbolToFourRecordIds.getOrDefault(symbol, new ArrayList<>());
            List<FourHoursCandles> monthlyCandles = new ArrayList<>();
            for (Long fid : fourIds) {
                monthlyCandles.addAll(fourCandlesByRecordId.getOrDefault(fid, new ArrayList<>()));
            }
            if (!monthlyCandles.isEmpty()) {
                monthlyHigh = monthlyCandles.stream().mapToDouble(FourHoursCandles::getHighPrice).max().orElse(0.0);
                monthlyLow = monthlyCandles.stream().mapToDouble(FourHoursCandles::getLowPrice).min().orElse(0.0);
                if (prevClose == 0.0) {
                    FourHoursCandles lastCandle = monthlyCandles.get(monthlyCandles.size() - 1);
                    prevClose = lastCandle.getClosePrice();
                    if (prevOpen == 0.0) prevOpen = lastCandle.getOpenPrice();
                    if (prevHigh == 0.0) prevHigh = lastCandle.getHighPrice();
                    if (prevLow == 0.0) prevLow = lastCandle.getLowPrice();
                }
            }

            FNOHighLow fno = FNOHighLow.builder()
                    .tradingSymbol(symbol)
                    .previousOpen(prevOpen)
                    .previousClose(prevClose)
                    .previousHigh(prevHigh)
                    .previousLow(prevLow)
                    .weeklyHigh(weeklyHigh)
                    .weeklyLow(weeklyLow)
                    .monthlyHigh(monthlyHigh)
                    .monthlyLow(monthlyLow)
                    .createdDate(today)
                    .build();

            aggregatesToSave.add(fno);
        }

        return aggregatesToSave;
    }

    private Map<Long, List<FourHoursCandles>> fourCandlesByRecord(List<FourHoursCandles> fourHoursCandles) {
        return fourHoursCandles.stream()
                .collect(Collectors.groupingBy(FourHoursCandles::getFourRecordId));
    }

    public List<Map<String, Object>> findBullishBreakoutPattern(String symbolFilter, double volumeFactor,
            double priceGrowthPercent, boolean crossedHighsOnly) {
        List<OneHourRecord> allRecords = (symbolFilter != null && !symbolFilter.trim().isEmpty())
                ? oneHourRecordRepository.findBySymbol(symbolFilter)
                : oneHourRecordRepository.findAll();

        if (allRecords.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, List<OneHourRecord>> recordsBySymbol = allRecords.stream()
                .collect(Collectors.groupingBy(OneHourRecord::getSymbol));

        List<Map<String, Object>> results = new ArrayList<>();

        for (Map.Entry<String, List<OneHourRecord>> entry : recordsBySymbol.entrySet()) {
            String sym = entry.getKey();
            List<OneHourRecord> symRecords = entry.getValue();

            symRecords.sort(Comparator.comparing(r -> r.getEnteredDate() != null ? r.getEnteredDate() : LocalDate.MIN));

            List<Long> recordIds = symRecords.stream()
                    .map(OneHourRecord::getOneId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (recordIds.isEmpty()) {
                continue;
            }

            List<OneHourCandles> candles = oneHourCandlesRepository.findByOneRecordIdsIn(recordIds);
            if (candles.isEmpty()) {
                continue;
            }

            Map<Long, List<OneHourCandles>> candlesByRecordId = candles.stream()
                    .collect(Collectors.groupingBy(OneHourCandles::getOneRecordId));

            List<OneHourCandles> allCandles = new ArrayList<>();
            for (OneHourRecord r : symRecords) {
                List<OneHourCandles> dayCandles = candlesByRecordId.get(r.getOneId());
                if (dayCandles != null && !dayCandles.isEmpty()) {
                    dayCandles.sort(Comparator.comparingInt(OneHourCandles::getCount));
                    allCandles.addAll(dayCandles);
                }
            }

            int totalCandles = allCandles.size();
            if (totalCandles < 7) {
                continue;
            }

            List<OneHourCandles> last7Candles = allCandles.subList(totalCandles - 7, totalCandles);

            boolean isRaisingUp = true;
            for (int i = 1; i < 7; i++) {
                OneHourCandles prev = last7Candles.get(i - 1);
                OneHourCandles curr = last7Candles.get(i);
                if (curr.getClosePrice() <= prev.getClosePrice()) {
                    isRaisingUp = false;
                    break;
                }
            }

            if (!isRaisingUp) {
                continue;
            }

            double last7AvgVolume = last7Candles.stream()
                    .mapToLong(OneHourCandles::getVolume)
                    .average()
                    .orElse(0.0);

            double maxPrev7AvgVolume = 0.0;
            boolean hasPrevWindows = false;

            for (int j = 0; j <= totalCandles - 8; j++) {
                hasPrevWindows = true;
                List<OneHourCandles> prevWindow = allCandles.subList(j, j + 7);
                double windowAvgVol = prevWindow.stream()
                        .mapToLong(OneHourCandles::getVolume)
                        .average()
                        .orElse(0.0);

                if (windowAvgVol > maxPrev7AvgVolume) {
                    maxPrev7AvgVolume = windowAvgVol;
                }
            }

            double requiredVolFactor = (volumeFactor > 0) ? volumeFactor : 1.0;
            boolean isHighestVolume = !hasPrevWindows
                    || (maxPrev7AvgVolume > 0 && last7AvgVolume >= maxPrev7AvgVolume * requiredVolFactor)
                    || (maxPrev7AvgVolume == 0 && last7AvgVolume > 0);

            if (!isHighestVolume) {
                continue;
            }

            OneHourRecord lastRecord = symRecords.get(symRecords.size() - 1);
            double startPrice = last7Candles.get(0).getOpenPrice();
            double endPrice = last7Candles.get(6).getClosePrice();
            double growthPct = startPrice > 0 ? ((endPrice - startPrice) / startPrice) * 100.0 : 0.0;

            if (priceGrowthPercent > 0 && growthPct < priceGrowthPercent) {
                continue;
            }

            Map<String, Object> match = new LinkedHashMap<>();
            match.put("symbol", sym);
            match.put("recordId", lastRecord.getOneId());
            match.put("date", lastRecord.getEnteredDate());
            match.put("last_7_avg_volume", Math.round(last7AvgVolume));
            match.put("prev_max_7_avg_volume", Math.round(maxPrev7AvgVolume));
            match.put("vol_increase_factor", maxPrev7AvgVolume > 0 ? String.format("%.2fx", last7AvgVolume / maxPrev7AvgVolume) : "N/A");
            match.put("start_price", startPrice);
            match.put("end_price", endPrice);
            match.put("total_price_growth_pct", String.format("%.2f%%", growthPct));
            match.put("candles", last7Candles);

            log.info("[PatternDetect] Found 7-candle raising pattern match for symbol: {}, avgVol: {}, prevMaxAvgVol: {}",
                    sym, Math.round(last7AvgVolume), Math.round(maxPrev7AvgVolume));

            results.add(match);
        }

        return results;
    }

    public List<OneHourIndexRecord> processAllOneHourIndex(String startTime, String endTime) {
        return processIndex1Hours(startTime, endTime);
    }

    public List<OneHourIndexRecord> getAllOneHourIndexRecordsWithCandles() {
        List<OneHourIndexRecord> records = oneHourIndexRecordRepository.findAll();
        if (records.isEmpty()) {
            return records;
        }
        List<Long> recordIds = records.stream()
                .map(OneHourIndexRecord::getOneId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (recordIds.isEmpty()) {
            return records;
        }
        List<OneHourIndexCandles> allCandles = oneHourIndexCandlesRepository.findByOneRecordIdsIn(recordIds);
        Map<Long, List<OneHourIndexCandles>> candlesByRecordId = allCandles.stream()
                .collect(Collectors.groupingBy(OneHourIndexCandles::getOneRecordId));
        for (OneHourIndexRecord rec : records) {
            List<OneHourIndexCandles> candles = candlesByRecordId.getOrDefault(rec.getOneId(), new ArrayList<>());
            candles.sort(Comparator.comparingInt(OneHourIndexCandles::getCount));
            rec.setCandles(candles);
        }

        Map<String, List<OneHourIndexRecord>> recordsBySymbol = records.stream()
                .filter(r -> r.getSymbol() != null)
                .collect(Collectors.groupingBy(OneHourIndexRecord::getSymbol));

        for (List<OneHourIndexRecord> symbolRecords : recordsBySymbol.values()) {
            symbolRecords.sort(Comparator.comparing(
                    r -> r.getEnteredDate() != null ? r.getEnteredDate() : LocalDate.MIN));

            for (int i = 0; i < symbolRecords.size(); i++) {
                OneHourIndexRecord currentRecord = symbolRecords.get(i);
                List<OneHourIndexCandles> currentCandles = currentRecord.getCandles();

                if (currentCandles != null && !currentCandles.isEmpty()) {
                    Map<String, List<OneHourIndexCandles>> candlesByDate = new LinkedHashMap<>();
                    for (OneHourIndexCandles c : currentCandles) {
                        String dateStr = (c.getTimestamp() != null && c.getTimestamp().length() >= 10 && c.getTimestamp().contains("-"))
                                ? c.getTimestamp().substring(0, 10)
                                : (currentRecord.getEnteredDate() != null ? currentRecord.getEnteredDate().toString() : "DEFAULT");
                        candlesByDate.computeIfAbsent(dateStr, k -> new ArrayList<>()).add(c);
                    }

                    List<String> sortedDates = new ArrayList<>(candlesByDate.keySet());
                    Collections.sort(sortedDates);

                    double todayClosing;
                    double previousDayClosing;

                    if (sortedDates.size() > 1) {
                        String latestDate = sortedDates.get(sortedDates.size() - 1);
                        String prevDate = sortedDates.get(sortedDates.size() - 2);

                        List<OneHourIndexCandles> todayCandles = candlesByDate.get(latestDate);
                        List<OneHourIndexCandles> prevCandles = candlesByDate.get(prevDate);

                        todayClosing = todayCandles.get(todayCandles.size() - 1).getClosePrice();
                        previousDayClosing = prevCandles.get(prevCandles.size() - 1).getClosePrice();
                    } else if (i > 0) {
                        todayClosing = currentCandles.get(currentCandles.size() - 1).getClosePrice();
                        OneHourIndexRecord prevRecord = symbolRecords.get(i - 1);
                        List<OneHourIndexCandles> prevCandles = prevRecord.getCandles();
                        if (prevCandles != null && !prevCandles.isEmpty()) {
                            previousDayClosing = prevCandles.get(prevCandles.size() - 1).getClosePrice();
                        } else {
                            previousDayClosing = currentCandles.get(0).getOpenPrice();
                        }
                    } else {
                        todayClosing = currentCandles.get(currentCandles.size() - 1).getClosePrice();
                        previousDayClosing = currentCandles.get(0).getOpenPrice();
                    }

                    double difference = todayClosing - previousDayClosing;
                    double pct = previousDayClosing != 0 ? (difference / (previousDayClosing / 100.0)) : 0.0;

                    currentRecord.setTodayClosing(todayClosing);
                    currentRecord.setPreviousDayClosing(previousDayClosing);
                    currentRecord.setPercentageChange(pct);
                }
            }
        }

        return records;
    }

    private static <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        if (list == null || list.isEmpty() || size <= 0) return partitions;
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
