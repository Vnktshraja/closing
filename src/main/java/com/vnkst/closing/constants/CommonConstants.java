package com.multiplier.reactive.constants;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

public class CommonConstants {
    public static final String FNO = "FNO";
    public static final String OPEN = "OPEN";
    public static final String ACTIVE = "ACTIVE";
    public static final String CLOSED = "CLOSED";
    public static final String CONNECTED = "Connected";
    public static final String NOT_CONNECTED = "Not_Connected";
    public static final String CLOSE_WEBSOCKET = "CLOSE_WEBSOCKET";
    public static final String BUY_ORDER = "BUY_ORDER";
    public static final String SELL_ORDER = "SELL_ORDER";
    public static final String SOLD = "SOLD";
    public static final String PRICE = "price";
    public static final String QUANTITY = "quantity";
    public static final String WS1 = "WS1";
    public static final String WS2  = "WS2";
    public static final String WS3  = "WS3";
    public static final String WS4  = "WS4";
    public static final String WS7  = "WS7";
    public static final String BUY = "BUY";
    public static final String SELL = "SELL";
    public static final String PROFIT = "PROFIT";
    public static final String LOSS = "LOSS";
    public static final String FNO_PRODUCT = "NRML";
    public static final String CASH = "CASH";
    public static final String EQUITY_PRODUCT = "CNC";
    public static final String AUTO = "AUTO";
    public static final String MANUAL = "MANUAL";
    public static final String SENSEX = "SENSEX";
    public static final String NIFTY = "NIFTY";
    public static final LocalDate DATE = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    public static final LocalTime TIME = LocalTime.now(ZoneId.of("Asia/Kolkata"));
    public static final LocalDateTime TODAY_DATE_TIME = DATE.atTime(TIME);
    // ✅ Configurable limits (can be externalized to properties)
    public static final int MAX_CONCURRENT_CALLS = 20; // Prevents overload (parallel requests)
    public static final double REQUESTS_PER_SECOND = 10.0; // Respects API rate limits (300/min = 5.0/sec, using 4.8 for safety margin)
    public static final String APP_JSON = "application/json";
    public static final LocalTime MID_NIGHT = LocalTime.MIDNIGHT;
    public static final LocalTime FIVE_AM = LocalTime.of(5, 0);
}
