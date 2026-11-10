package com.vnkst.closing.cache;


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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
@Setter
@Getter
@Slf4j
public class DataCache {

    private ConcurrentHashMap<String, Integer> lotSizeCache = new ConcurrentHashMap<>();


    private Set<String> nameCache = ConcurrentHashMap.newKeySet();
    private ConcurrentHashMap<String, Double> ltpCache = new ConcurrentHashMap<>();

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

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
    }


}
