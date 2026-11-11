package com.vnkst.closing.service;

import com.vnkst.closing.cache.DataCache;
import com.vnkst.closing.entity.AccessToken;
import com.vnkst.closing.entity.Orders;
import com.vnkst.closing.repository.OrdersRepository;
import com.vnkst.closing.utils.TempUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.vnkst.closing.constants.CommonConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class GrowwServer {
    private final TokenCache tokenCache;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final DataCache dataCache;
    private final OrdersRepository ordersRepository;
    private final ClientHttp clientHttp;

    @Value("${groww_api_key:}")
    private String apiKey;

    public AccessToken generateToken(String checksum) throws IOException, InterruptedException {
        return generateToken(checksum, Instant.now().getEpochSecond());
    }

    public AccessToken generateToken(String checksum, long timestamp) throws IOException, InterruptedException {
        String keyType = "approval";
        String jsonBody = String.format("""
            {
                "key_type": "%s",
                "checksum": "%s",
                "timestamp": "%s"
            }
            """, keyType, checksum, timestamp);

        String baseUrl = "https://api.groww.in/v1/token/api/access";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", APP_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response == null) {
            throw new IOException("Empty response body");
        }
        if (response.statusCode() != 200) {
            log.error("Groww token API returned HTTP {}: {}", response.statusCode(), response.body());
            throw new IOException("Groww token API error: HTTP " + response.statusCode() + " - " + response.body());
        }
        try {
            if (response.body().contains("header")) {
                log.warn("Data from Groww while getting token API: Token key not found or inactive");
                return null;
            }
            AccessToken accessToken = mapper.readValue(response.body(), AccessToken.class);
            if (accessToken.getToken() == null) {
                log.error("Groww response missing token. Response: {}", response.body());
                throw new IOException("Groww response missing token: " + response.body());
            }
            return accessToken;
        } catch (Exception e) {
            log.error("Failed to parse Groww token response: {}", response.body(), e);
            throw new IOException("Failed to parse market data", e);
        }
    }

    public String callApi(String url) {
        return executeGetWithToken(url, "testKey1");
    }

    public String callApi2(String url) {
        return executeGetWithToken(url, "testKey2");
    }

    public String callApi3(String url) {
        return executeGetWithToken(url, "testKey3");
    }

    private String executeGetWithToken(String url, String tokenKey) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + tokenCache.get(tokenKey))
                .header("Accept", APP_JSON)
                .header("X-API-VERSION", "1.0")
                .GET()
                .build();

        try {
            HttpResponse<String> res = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                return res.body();
            }
            if (res.statusCode() == 429) {
                String retryAfter = res.headers().firstValue("Retry-After").orElse("1");
                int waitTime = Integer.parseInt(retryAfter);
                Thread.sleep(waitTime * 1000L);
                return executeGetWithToken(url, tokenKey);
            }
            log.error("Unexpected status code {} for URL={}", res.statusCode(), url);
            return "FAIL " + res.statusCode() + " " + url;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while calling URL={}", url, ie);
            return "ERROR " + url + " -> " + ie.getMessage();
        } catch (Exception e) {
            log.error("Exception while calling URL={}", url, e);
            return "ERROR " + url + " -> " + e.getMessage();
        }
    }

    public void calculateHoldings() {
        try {
            String result = clientHttp.getHoldingsFromGroww();
            JsonNode record = mapper.readTree(result);
            JsonNode listOfPositions = record.path("payload").path("positions");
            List<Orders> existingOrders = ordersRepository.getActiveOrTodayOrders(LocalDate.now());
            Map<String, Orders> orderMap = existingOrders.stream()
                    .collect(Collectors.toMap(Orders::getSymbol, o -> o, (o1, o2) -> o1));

            for (JsonNode position : listOfPositions) {
                String symbol = position.path("trading_symbol").asText();
                String segment;
                if (Objects.isNull(symbol) || "null".equalsIgnoreCase(symbol)) {
                    symbol = position.path("symbol_isin").asText();
                    segment = TempUtil.isFno(symbol) ? "FNO" : "CASH";
                } else {
                    segment = position.path("segment").asText();
                }
                int quantity = position.path("quantity").asInt();
                String currentState = quantity == 0 ? SOLD : ACTIVE;

                if (!dataCache.alreadyExist(symbol) && currentState.equalsIgnoreCase("ACTIVE")) {
                    double netPrice = position.path("net_price").asDouble();
                    Map<String, Object> valueMap = new HashMap<>();
                    valueMap.put("price", netPrice);
                    valueMap.put("quantity", quantity);
                    valueMap.put("order_type", MANUAL);
                    dataCache.addActiveSymbols(symbol, valueMap);
                }

                if (orderMap.containsKey(symbol)) {
                    Orders existing = orderMap.get(symbol);
                    if (!currentState.equalsIgnoreCase(existing.getOrder_state())) {
                        updateForManualOrders(existing, position, symbol, segment);
                    }
                } else {
                    Orders newOrders = new Orders();
                    newOrders.setNew(true);
                    updateForManualOrders(newOrders, position, symbol, segment);
                }
            }
        } catch (Exception e) {
            log.error("Failed to calculate holdings from Groww", e);
        }
    }

    private Orders updateForManualOrders(Orders order, JsonNode position, String symbol, String segment) {
        if (order.getGroww_order_id() != null) {
            order.setNew(false);
        }
        String options = symbol.substring(symbol.length() - 2);
        order.setOptions(options);
        order.setOrder_type(MANUAL);
        order.setSymbol(symbol);
        order.setSegment(segment);
        double netPrice = position.path("net_price").asDouble();
        int quantity = position.path("quantity").asInt();
        order.setQuantity(quantity);
        String state = quantity == 0 ? SOLD : ACTIVE;
        if (ACTIVE.equalsIgnoreCase(state) && !dataCache.alreadyExist(symbol)) {
            Map<String, Object> valueMap = new HashMap<>();
            valueMap.put(PRICE, netPrice);
            valueMap.put(QUANTITY, quantity);
            valueMap.put("order_type", MANUAL);
            dataCache.addActiveSymbols(symbol, valueMap);
        } else if (SOLD.equalsIgnoreCase(state) && dataCache.alreadyExist(symbol)) {
            dataCache.removeSymbols(symbol);
        }
        order.setOrder_state(state);

        double pnl = position.path("realised_pnl").asDouble();
        order.setStatus("SUCCESS");
        order.setTransaction_date(DATE);
        if (!ACTIVE.equalsIgnoreCase(state)) {
            order.setOrder_closed_Time(TODAY_DATE_TIME);
            order.setPnl(pnl);
            order.setTransaction_type(SELL);
            order.setSoldPrice(netPrice);
            if (order.getClosing_reason() == null) {
                order.setClosing_reason("MANUAL_EXTERNAL_GROWW_SYNC: Position closed externally on Groww");
            }
        } else {
            order.setOrder_placed_Time(TODAY_DATE_TIME);
            order.setTransaction_type(BUY);
            order.setBoughtPrice(netPrice);
        }
        if (order.getGroww_order_id() == null) {
            String id = UUID.randomUUID().toString().replace("-", "");
            order.setGroww_order_id(id);
        }
        Optional<Orders> existing = ordersRepository.getOrderBySymbolStateAndDate(symbol, state, DATE);
        if (existing.isPresent()) {
            log.info("[Manual-Orders] Order for {} with state {} on date {} already exists. Skipping save.", symbol, state, DATE);
            return existing.get();
        }
        return ordersRepository.save(order);
    }

    public Double getOnlyBalance() {
        try {
            String response = clientHttp.getBalance();
            JsonNode record = mapper.readTree(response);
            return record.path("payload").path("fno_margin_details").path("future_balance_available").asDouble();
        } catch (Exception exception) {
            log.error("Error fetching/parsing balance: {}", exception.getMessage());
            return 0.0;
        }
    }
}
