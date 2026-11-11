package com.vnkst.closing.service;

import com.vnkst.closing.cache.DataCache;
import com.vnkst.closing.entity.FNOHighLow;
import com.vnkst.closing.entity.FourHoursCandles;
import com.vnkst.closing.entity.FourHoursRecord;
import com.vnkst.closing.entity.SymbolUrl;
import com.vnkst.closing.repository.FourHoursCandlesRepository;
import com.vnkst.closing.repository.FourHoursRecordRepository;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.vnkst.closing.constants.CommonConstants.REQUESTS_PER_SECOND;

@Service
@RequiredArgsConstructor
@Slf4j
public class FourHoursService {

    private final GrowwServer growwServer;
    private final DailyUrlProvider dailyUrlProvider;
    private final FourHoursRecordRepository fourHoursRecordRepository;
    private final FourHoursCandlesRepository fourHoursCandlesRepository;
    private final ObjectMapper mapper;
    private final DataCache dataCache;

    public void process4Hours(String startTime, String endTime) {
        process4Hours(startTime, endTime, true);
    }

    public void truncateTable() {
        fourHoursRecordRepository.truncateTable();
    }

    public List<FourHoursRecord> process4Hours(String startTime, String endTime, boolean saveToDb) {
        String decodedEnd = URLDecoder.decode(endTime, StandardCharsets.UTF_8);
        LocalDate enteredDate = LocalDate.parse(decodedEnd.substring(0, 10));
        return save4HoursDaily(dailyUrlProvider.getDailyFourHourUrls(startTime, endTime), enteredDate, saveToDb);
    }

    public List<FourHoursRecord> process4HoursForSymbols(Collection<String> symbols, String startTime, String endTime) {
        String decodedEnd = URLDecoder.decode(endTime, StandardCharsets.UTF_8);
        LocalDate enteredDate = LocalDate.parse(decodedEnd.substring(0, 10));
        return save4HoursDailyForSymbols(symbols, dailyUrlProvider.getDailyFourHourUrls(symbols, startTime, endTime), enteredDate);
    }

    @Transactional
    public List<FourHoursRecord> save4HoursDailyForSymbols(Collection<String> symbols, List<SymbolUrl> symbolUrls, LocalDate enteredDate) {
        try {
            List<FourHoursRecord> records = new ArrayList<>();
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
                String response = growwServer.callApi(req.url());
                FourHoursRecord record = convertToEntity(req.symbol(), response, enteredDate);
                if (record != null) {
                    record.setFourId(null);
                    records.add(record);
                }
            }

            if (records.isEmpty()) {
                return new ArrayList<>();
            }

            if (symbols != null && !symbols.isEmpty()) {
                fourHoursRecordRepository.deleteBySymbolIn(symbols);
            }

            List<FourHoursRecord> savedRecords = fourHoursRecordRepository.saveAll(records);
            Map<String, Long> keyToIdMap = new HashMap<>();
            for (FourHoursRecord saved : savedRecords) {
                String key = saved.getSymbol() + "_" + saved.getStartTime();
                keyToIdMap.put(key, saved.getFourId());
            }

            List<FourHoursCandles> allCandles = new ArrayList<>();
            for (FourHoursRecord originalRecord : records) {
                String key = originalRecord.getSymbol() + "_" + originalRecord.getStartTime();
                Long parentId = keyToIdMap.get(key);
                if (parentId != null) {
                    originalRecord.setFourId(parentId);
                    boolean isActive = dataCache.alreadyExist(originalRecord.getSymbol());
                    if (originalRecord.getCandles() != null) {
                        int count = 1;
                        for (FourHoursCandles candle : originalRecord.getCandles()) {
                            candle.setFourCandleId(null);
                            candle.setFourRecordId(parentId);
                            candle.setCount(count++);
                            allCandles.add(candle);
                            if (isActive) {
                                dataCache.addLatest4HourCandle(originalRecord.getSymbol(), candle);
                            }
                        }
                    }
                }
            }

            if (!allCandles.isEmpty()) {
                for (int i = 0; i < allCandles.size(); i += 5000) {
                    List<FourHoursCandles> batch = allCandles.subList(i, Math.min(i + 5000, allCandles.size()));
                    fourHoursCandlesRepository.saveAll(batch);
                }
            }

            return savedRecords;
        } catch (Exception e) {
            log.error("Error saving 4-hour records for symbols {}: {}", symbols, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @Transactional
    public List<FourHoursRecord> save4HoursDaily(List<SymbolUrl> symbolUrls, LocalDate enteredDate, boolean saveToDb) {
        try {
            List<FourHoursRecord> records = new ArrayList<>();
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
                String response = growwServer.callApi(req.url());
                FourHoursRecord record = convertToEntity(req.symbol(), response, enteredDate);
                if (record != null) {
                    record.setFourId(null);
                    records.add(record);
                }
            }

            if (!saveToDb || records.isEmpty()) {
                return records;
            }

            fourHoursRecordRepository.truncateTable();
            List<FourHoursRecord> savedRecords = fourHoursRecordRepository.saveAll(records);

            Map<String, Long> keyToIdMap = new HashMap<>();
            for (FourHoursRecord saved : savedRecords) {
                String key = saved.getSymbol() + "_" + saved.getStartTime();
                keyToIdMap.put(key, saved.getFourId());
            }

            List<FourHoursCandles> allCandles = new ArrayList<>();
            for (FourHoursRecord originalRecord : records) {
                String key = originalRecord.getSymbol() + "_" + originalRecord.getStartTime();
                Long parentId = keyToIdMap.get(key);
                if (parentId != null) {
                    originalRecord.setFourId(parentId);
                    boolean isActive = dataCache.alreadyExist(originalRecord.getSymbol());
                    if (originalRecord.getCandles() != null) {
                        int count = 1;
                        for (FourHoursCandles candle : originalRecord.getCandles()) {
                            candle.setFourCandleId(null);
                            candle.setFourRecordId(parentId);
                            candle.setCount(count++);
                            allCandles.add(candle);
                            if (isActive) {
                                dataCache.addLatest4HourCandle(originalRecord.getSymbol(), candle);
                            }
                        }
                    }
                }
            }

            if (!allCandles.isEmpty()) {
                for (int i = 0; i < allCandles.size(); i += 5000) {
                    List<FourHoursCandles> batch = allCandles.subList(i, Math.min(i + 5000, allCandles.size()));
                    fourHoursCandlesRepository.saveAll(batch);
                }
            }

            return savedRecords;
        } catch (Exception e) {
            log.error("Error due to : {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private FourHoursRecord convertToEntity(String symbol, String response, LocalDate enteredDate) {
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

            FourHoursRecord record = new FourHoursRecord();
            record.setSymbol(symbol);
            record.setStartTime(formatRawTimeToHHmm(payload.path("start_time").asText()));
            record.setEndTime(formatRawTimeToHHmm(payload.path("end_time").asText()));
            record.setEnteredDate(enteredDate);
            record.setUpdatedTime(LocalTime.now());
            record.setIntervalInMinutes(payload.path("interval_in_minutes").asInt());
            JsonNode candlesFromGrow = payload.path("candles");
            List<FourHoursCandles> candlesList = new ArrayList<>();
            if (candlesFromGrow.isArray()) {
                int candleCount = 1;
                for (JsonNode candleNode : candlesFromGrow) {
                    if (candleNode.isArray() && candleNode.size() >= 6) {
                        FourHoursCandles candle = new FourHoursCandles();

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

    public List<Map<String, Object>> findBullishBreakoutPattern(String symbol, double volumeFactor,
            double priceGrowthPercent, boolean crossedHighsOnly) {
        List<FourHoursRecord> records;
        if (symbol != null && !symbol.trim().isEmpty()) {
            records = fourHoursRecordRepository.findBySymbol(symbol);
        } else {
            records = fourHoursRecordRepository.findAll();
        }

        List<Map<String, Object>> allMatchedDays = new ArrayList<>();
        for (FourHoursRecord record : records) {
            List<FourHoursCandles> candles = fourHoursCandlesRepository.findByFourRecordId(record.getFourId());
            candles.sort(Comparator.comparingInt(FourHoursCandles::getCount));

            List<List<FourHoursCandles>> daysList = new ArrayList<>();
            List<FourHoursCandles> currentDay = null;

            for (FourHoursCandles candle : candles) {
                if ("09:15".equals(candle.getTimestamp())) {
                    if (currentDay != null) {
                        daysList.add(currentDay);
                    }
                    currentDay = new ArrayList<>();
                }
                if (currentDay != null) {
                    currentDay.add(candle);
                }
            }
            if (currentDay != null) {
                daysList.add(currentDay);
            }

            for (List<FourHoursCandles> dayCandles : daysList) {
                FourHoursCandles c1 = dayCandles.stream()
                        .filter(c -> "09:15".equals(c.getTimestamp()))
                        .findFirst()
                        .orElse(null);

                FourHoursCandles c2 = dayCandles.stream()
                        .filter(c -> "13:15".equals(c.getTimestamp()))
                        .findFirst()
                        .orElse(null);

                if (c1 != null && c2 != null) {
                    boolean bothGreen = "GREEN".equals(c1.getColour()) && "GREEN".equals(c2.getColour());
                    if (bothGreen) {
                        boolean passesCrossedHighs = true;
                        if (crossedHighsOnly) {
                            passesCrossedHighs = false;
                            FNOHighLow prev = dataCache.getFnoHighAndLow().get(record.getSymbol());
                            if (prev != null) {
                                double mHigh = prev.getMonthlyHigh();
                                double wHigh = prev.getWeeklyHigh();
                                if (mHigh > 0 && wHigh > 0 && c1.getClosePrice() > mHigh
                                        && c1.getClosePrice() > wHigh) {
                                    passesCrossedHighs = true;
                                }
                            }
                        }

                        if (passesCrossedHighs) {
                            long v1 = c1.getVolume();
                            long v2 = c2.getVolume();
                            double p1 = c1.getClosePrice();
                            double p2 = c2.getClosePrice();

                            boolean volSurge = v1 > 0 && ((double) v2 / v1) >= volumeFactor;
                            boolean priceSurge = p1 > 0 && ((p2 - p1) / p1) * 100.0 >= priceGrowthPercent;

                            if (volSurge && priceSurge) {
                                Map<String, Object> match = new HashMap<>();
                                match.put("symbol", record.getSymbol());
                                match.put("recordId", record.getFourId());
                                match.put("date", record.getEnteredDate());
                                match.put("c1_close", p1);
                                match.put("c2_close", p2);
                                match.put("price_change_pct",
                                        String.format("%.2f%%", ((p2 - p1) / p1) * 100.0));
                                match.put("c1_vol", v1);
                                match.put("c2_vol", v2);
                                match.put("vol_increase_factor", String.format("%.2fx", (double) v2 / v1));
                                match.put("c1_candle_id", c1.getFourCandleId());
                                match.put("c2_candle_id", c2.getFourCandleId());
                                allMatchedDays.add(match);
                            }
                        }
                    }
                }
            }
        }
        return allMatchedDays;
    }
}
