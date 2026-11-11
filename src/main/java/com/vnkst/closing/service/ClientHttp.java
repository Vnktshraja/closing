package com.vnkst.closing.service;

import com.vnkst.closing.entity.LtpResponse;
import com.vnkst.closing.entity.OrderRequest;
import com.vnkst.closing.entity.OrderResponse;
import com.vnkst.closing.utils.TempUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.vnkst.closing.constants.CommonConstants.APP_JSON;

@Service
@Slf4j
@RequiredArgsConstructor
public class ClientHttp {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    @Autowired
    private TokenCache tokenCache;

    public String getHoldingsFromGroww() throws IOException, InterruptedException {
        String baseUrl = "https://api.groww.in/v1/positions/user";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Authorization", "Bearer " + tokenCache.get("testKey1"))
                .header("Accept", APP_JSON)
                .header("X-API-VERSION", "1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response == null || response.body() == null) {
            throw new IOException("Empty response body");
        }
        return response.body();
    }

    public OrderResponse placeOrderOnGroww(OrderRequest orderRequest) {
        try {
            String jsonBody = mapper.writeValueAsString(orderRequest);
            String tokens = "Bearer " + tokenCache.get("testKey1");
            log.info("Tokens: {}", tokens);
            String baseUrl = "https://api.groww.in/v1/order/create";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Content-Type", APP_JSON)
                    .header("Accept", APP_JSON)
                    .header("Authorization", "Bearer " + tokenCache.get("testKey1"))
                    .header("X-API-VERSION", "1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response == null || response.body() == null) {
                log.error("Empty response body from Groww order create");
                return null;
            }
            log.info("Response for place order from groww : {}", response.body());
            return mapper.readValue(response.body(), OrderResponse.class);
        } catch (Exception e) {
            log.error("Failed to place/parse order response on Groww", e);
            return null;
        }
    }

    public String closeOrder(String growwOrderId) throws IOException, InterruptedException {
        Map<String, String> cancelOrderRequest = new LinkedHashMap<>();
        cancelOrderRequest.put("segment", "FNO");
        cancelOrderRequest.put("groww_order_id", growwOrderId);
        String jsonBody = mapper.writeValueAsString(cancelOrderRequest);

        String baseUrl = "https://api.groww.in/v1/order/cancel";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Content-Type", APP_JSON)
                .header("Accept", APP_JSON)
                .header("Authorization", "Bearer " + tokenCache.get("testKey1"))
                .header("X-API-VERSION", "1.0")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response == null || response.body() == null) {
            throw new IOException("Empty response body");
        }
        log.info("Response for cancel order from groww : {}", response.body());
        return response.body();
    }

    public String modifyOrder(String jsonBody) throws IOException, InterruptedException {
        String baseUrl = "https://api.groww.in/v1/order/modify";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Content-Type", APP_JSON)
                .header("Accept", APP_JSON)
                .header("Authorization", "Bearer " + tokenCache.get("testKey1"))
                .header("X-API-VERSION", "1.0")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response == null || response.body() == null) {
            throw new IOException("Empty response body");
        }
        return response.body();
    }

    public String getBalance() throws IOException, InterruptedException {
        String baseUrl = "https://api.groww.in/v1/margins/detail/user";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Accept", APP_JSON)
                .header("Authorization", "Bearer " + tokenCache.get("testKey1"))
                .header("X-API-VERSION", "1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response == null || response.body() == null) {
            throw new IOException("Empty response body");
        }
        return response.body();
    }

    public Map<String, Double> getLiveLtp(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }

        List<String> fnoSymbols = new ArrayList<>();
        List<String> cashSymbols = new ArrayList<>();

        for (String s : symbols) {
            if (s != null && !s.isBlank()) {
                String clean = s.trim();
                if (TempUtil.isFno(clean)) {
                    fnoSymbols.add(clean);
                } else {
                    cashSymbols.add(clean);
                }
            }
        }

        Map<String, Double> combined = new HashMap<>();
        if (!fnoSymbols.isEmpty()) {
            combined.putAll(fetchLtpForSegment("FNO", fnoSymbols));
        }
        if (!cashSymbols.isEmpty()) {
            combined.putAll(fetchLtpForSegment("CASH", cashSymbols));
        }

        return combined;
    }

    private Map<String, Double> fetchLtpForSegment(String segment, List<String> symbols) {
        List<String> formattedSymbols = symbols.stream()
                .map(s -> (s.startsWith("NSE_") || s.startsWith("BSE_") || s.startsWith("NSE:") || s.startsWith("BSE:"))
                        ? s
                        : ("NSE_" + s))
                .collect(Collectors.toList());

        String symbolsParam = String.join(",", formattedSymbols);
        String baseUrl = "https://api.groww.in/v1/live-data/ltp?segment=" + segment + "&exchange_symbols=" + symbolsParam;
        log.info("[Groww-LTP] Fetching LTP for segment={}, symbols={}", segment, symbolsParam);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Accept", APP_JSON)
                .header("Authorization", "Bearer " + tokenCache.get("testKey1"))
                .header("X-API-VERSION", "1.0")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response == null || response.body() == null) {
                log.error("[Groww-LTP] Empty response for segment={}, symbols={}", segment, symbolsParam);
                return Map.of();
            }
            log.info("[Groww-LTP] Raw response for segment={}, status={}: {}", segment, response.statusCode(), response.body());
            LtpResponse ltpResponse = mapper.readValue(response.body(), LtpResponse.class);
            Map<String, Double> rawPayload = ltpResponse.getPayload();
            if (rawPayload == null || rawPayload.isEmpty()) {
                log.warn("[Groww-LTP] No LTP payload returned for segment={}, symbols={}", segment, symbolsParam);
                return Map.of();
            }

            Map<String, Double> normalized = new HashMap<>();
            for (Map.Entry<String, Double> entry : rawPayload.entrySet()) {
                String key = entry.getKey();
                Double price = entry.getValue();
                if (key != null && price != null) {
                    normalized.put(key, price);
                    String clean = key.replaceAll("^(NSE|BSE)[_:]", "");
                    normalized.put(clean, price);
                    for (String orig : symbols) {
                        if (orig.equalsIgnoreCase(clean) || orig.equalsIgnoreCase(key)) {
                            normalized.put(orig, price);
                        }
                    }
                }
            }
            log.info("[Groww-LTP] Normalized LTP results for segment {}: {}", segment, normalized);
            return normalized;
        } catch (Exception e) {
            log.error("[Groww-LTP] Request failed for segment={}, symbols={}: {}", segment, symbolsParam, e.getMessage());
            return Map.of();
        }
    }
}
