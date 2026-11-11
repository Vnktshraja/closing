package com.vnkst.closing.service;

import com.vnkst.closing.cache.DataCache;
import com.vnkst.closing.entity.PredictedStock;
import com.vnkst.closing.entity.SymbolUrl;
import com.vnkst.closing.repository.GrowwInstrumentRepository;
import com.vnkst.closing.repository.PredictedStockRepository;
import com.vnkst.closing.utils.DatesUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.vnkst.closing.constants.CodeConstrants.july_list;

@Service
@Slf4j
@RequiredArgsConstructor
public class DailyUrlProvider {

        private static final String EXCHANGE = "NSE";
        private static final String CASH_SEGMENT = "CASH";

        private static final String FNO_SEGMENT = "FNO";
        private static final String ONE_DAY_INTERVAL_IN_MINUTES = "1440";
        private static final String ONE_MINUTES_INTERVAL_IN_MINUTES = "1";
        private static final String FIVE_MINUTES_INTERVAL_IN_MINUTES = "5";
        private static final String TEN_MINUTES_INTERVAL_IN_MINUTES = "10";
        private static final String ONE_HOUR_INTERVAL_IN_MINUTES = "60";
        private static final String FOUR_HOUR_INTERVAL_IN_MINUTES = "240";
        private static final String BASE_URL = "https://api.groww.in/v1/historical/candle/range";
        private final CustomDate todayTime;
        private final DataCache dataCache;
        private final PredictedStockRepository predictedStockRepository;
        private final GrowwInstrumentRepository growwInstrumentRepository;

        public List<SymbolUrl> getDailyUrls(String startDate, String endDate, String startTime, String endTime) {
                List<String> symbols = july_list;

                LocalDate start = LocalDate.parse(startDate.trim());
                LocalDate priorStart = start.minusDays(1);
                while (!DatesUtil.isWeekday(priorStart)) {
                        priorStart = priorStart.minusDays(1);
                }

                String formattedStartTime = startTime.trim();
                if (formattedStartTime.length() == 5) {
                        formattedStartTime += ":00";
                }
                String formattedEndTime = endTime.trim();
                if (formattedEndTime.length() == 5) {
                        formattedEndTime += ":00";
                }

                String morningStr = priorStart.toString() + " 00:00:00";
                String eveningStr = endDate.trim() + " " + formattedEndTime;

                String morning = URLEncoder.encode(morningStr, StandardCharsets.UTF_8);
                String evening = URLEncoder.encode(eveningStr, StandardCharsets.UTF_8);

                log.info("Starting URL generation");
                return symbols.stream()
                                .map(symbol -> new SymbolUrl(symbol, String.format(
                                                "%s?exchange=%s&segment=%s&trading_symbol=%s&start_time=%s&end_time=%s&interval_in_minutes=%s",
                                                BASE_URL,
                                                EXCHANGE,
                                                CASH_SEGMENT,
                                                symbol,
                                                morning,
                                                evening,
                                                ONE_DAY_INTERVAL_IN_MINUTES)))
                                .collect(Collectors.toList());
        }

        public List<SymbolUrl> getDailyMinuteUrls(String start, String end) {
                LocalDate previousWorkingDay = DatesUtil.getWorkingPreviousDay(LocalDateTime.now().toLocalDate());
                log.info("[getDailyMinuteUrls] Fetching predicted symbols for previous working day: {}",
                                previousWorkingDay);
                return predictedStockRepository.findByPredictionDate(previousWorkingDay)
                                .stream()
                                .map(PredictedStock::getSymbol)
                                .distinct()
                                .map(symbol -> new SymbolUrl(symbol, String.format(
                                                "%s?exchange=%s&segment=%s&trading_symbol=%s&start_time=%s&end_time=%s&interval_in_minutes=%s",
                                                BASE_URL,
                                                EXCHANGE,
                                                FNO_SEGMENT,
                                                symbol,
                                                start,
                                                end,
                                                ONE_MINUTES_INTERVAL_IN_MINUTES)))
                                .collect(Collectors.toList());
        }

        public List<SymbolUrl> getOneDayUrls() {
                Set<String> names = dataCache.getNames();
                String morning = todayTime.getEncodedMorning(1);
                String evening = todayTime.getEncodedEvening();
                log.info("Starting URL generation");
                return names.stream()
                                .map(symbol -> new SymbolUrl(symbol, String.format(
                                                "%s?exchange=%s&segment=%s&trading_symbol=%s&start_time=%s&end_time=%s&interval_in_minutes=%s",
                                                BASE_URL,
                                                EXCHANGE,
                                                FNO_SEGMENT,
                                                symbol,
                                                morning,
                                                evening,
                                                ONE_MINUTES_INTERVAL_IN_MINUTES)))
                                .collect(Collectors.toList());
        }

        public List<SymbolUrl> getDaily5MinuteUrls(String start, String end) {
                Set<String> names = dataCache.getNames();
                log.debug("Starting 5Min URL generation");
                return names.stream()
                                .map(symbol -> new SymbolUrl(symbol, String.format(
                                                "%s?exchange=%s&segment=%s&trading_symbol=%s&start_time=%s&end_time=%s&interval_in_minutes=%s",
                                                BASE_URL,
                                                EXCHANGE,
                                                FNO_SEGMENT,
                                                symbol,
                                                start,
                                                end,
                                                FIVE_MINUTES_INTERVAL_IN_MINUTES)))
                                .collect(Collectors.toList());
        }

        public List<SymbolUrl> getDaily10MinuteUrls(String start, String end) {
                Set<String> names = dataCache.getNames();
                log.debug("Starting 10Min URL generation");
                return names.stream()
                                .map(symbol -> new SymbolUrl(symbol, String.format(
                                                "%s?exchange=%s&segment=%s&trading_symbol=%s&start_time=%s&end_time=%s&interval_in_minutes=%s",
                                                BASE_URL,
                                                EXCHANGE,
                                                FNO_SEGMENT,
                                                symbol,
                                                start,
                                                end,
                                                TEN_MINUTES_INTERVAL_IN_MINUTES)))
                                .collect(Collectors.toList());
        }

        public List<SymbolUrl> getDaily1HoursUrls(String start, String end) {
                Set<String> names = dataCache.getNames();
                log.debug("Starting 1Hour URL generation");
                return names.stream()
                                .map(symbol -> new SymbolUrl(symbol, String.format(
                                                "%s?exchange=%s&segment=%s&trading_symbol=%s&start_time=%s&end_time=%s&interval_in_minutes=%s",
                                                BASE_URL,
                                                EXCHANGE,
                                                FNO_SEGMENT,
                                                symbol,
                                                start,
                                                end,
                                                ONE_HOUR_INTERVAL_IN_MINUTES)))
                                .collect(Collectors.toList());
        }

        public List<SymbolUrl> getDaily1HoursUrls(Collection<String> symbols, String start, String end) {
                if (symbols == null || symbols.isEmpty()) {
                        return List.of();
                }
                log.debug("Starting targeted 1Hour URL generation for symbols: {}", symbols);
                return symbols.stream()
                                .map(symbol -> new SymbolUrl(symbol, String.format(
                                                "%s?exchange=%s&segment=%s&trading_symbol=%s&start_time=%s&end_time=%s&interval_in_minutes=%s",
                                                BASE_URL,
                                                EXCHANGE,
                                                FNO_SEGMENT,
                                                symbol,
                                                start,
                                                end,
                                                ONE_HOUR_INTERVAL_IN_MINUTES)))
                                .collect(Collectors.toList());
        }

        public List<SymbolUrl> getDailyIndex1HoursUrls(String start, String end) {
                log.debug("Starting Index 1Hour URL generation");
                return growwInstrumentRepository.fetchIndexSymbol()
                                .stream()
                                .map(symbol -> {
                                        String exchangeType = "SENSEX".equalsIgnoreCase(symbol) ? "BSE" : EXCHANGE;
                                        return new SymbolUrl(symbol, String.format(
                                                        "%s?exchange=%s&segment=%s&trading_symbol=%s&start_time=%s&end_time=%s&interval_in_minutes=%s",
                                                        BASE_URL,
                                                        exchangeType,
                                                        CASH_SEGMENT,
                                                        symbol,
                                                        start,
                                                        end,
                                                        ONE_HOUR_INTERVAL_IN_MINUTES));
                                })
                                .collect(Collectors.toList());
        }

        public List<SymbolUrl> getDailyFourHourUrls(String start, String end) {
                Set<String> symbols = dataCache.getNames();
                log.debug("Starting 4Hour URL generation");
                return symbols.stream()
                                .map(symbol -> new SymbolUrl(symbol, String.format(
                                                "%s?exchange=%s&segment=%s&trading_symbol=%s&start_time=%s&end_time=%s&interval_in_minutes=%s",
                                                BASE_URL,
                                                EXCHANGE,
                                                FNO_SEGMENT,
                                                symbol,
                                                start,
                                                end,
                                                FOUR_HOUR_INTERVAL_IN_MINUTES)))
                                .collect(Collectors.toList());
        }

        public List<SymbolUrl> getDailyFourHourUrls(Collection<String> symbols, String start, String end) {
                if (symbols == null || symbols.isEmpty()) {
                        return List.of();
                }
                log.debug("Starting targeted 4Hour URL generation for symbols: {}", symbols);
                return symbols.stream()
                                .map(symbol -> new SymbolUrl(symbol, String.format(
                                                "%s?exchange=%s&segment=%s&trading_symbol=%s&start_time=%s&end_time=%s&interval_in_minutes=%s",
                                                BASE_URL,
                                                EXCHANGE,
                                                FNO_SEGMENT,
                                                symbol,
                                                start,
                                                end,
                                                FOUR_HOUR_INTERVAL_IN_MINUTES)))
                                .collect(Collectors.toList());
        }
}
