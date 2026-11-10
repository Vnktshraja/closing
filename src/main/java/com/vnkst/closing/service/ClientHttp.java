package com.multiplier.reactive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiplier.reactive.dto.LtpResponse;
import com.multiplier.reactive.dto.OrderResponse;
import com.multiplier.reactive.entity.OrderRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.multiplier.reactive.util.TempUtil;

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

import static com.multiplier.reactive.constants.CommonConstants.APP_JSON;


@Service
@Slf4j
@RequiredArgsConstructor
public class ClientHttp {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    @Autowired
    TokenCache tokenCache;

    public Mono<String> getHoldingsFromGroww() {
        String baseUrl = "https://api.groww.in/v1/positions/user";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Authorization","Bearer "+tokenCache.get("testKey1"))
                .header("Accept", APP_JSON)
                .header("X-API-VERSION","1.0")
                .GET()
                .build() ;
        return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .flatMap(response -> {
                    if (response == null || response.body() == null) {
                        return Mono.error(new IOException("Empty response body"));
                    }
                    return Mono.just(response.body());
                });
    }

    // this method place groww order.
    public Mono<OrderResponse> placeOrderOnGroww(OrderRequest orderRequest) {
        return Mono.fromCallable(() -> mapper.writeValueAsString(orderRequest))
                .flatMap(jsonBody -> {
                    String tokens = "Bearer "+tokenCache.get("testKey1");
                    log.info("Tokens: {}", tokens);
                    String baseUrl = "https://api.groww.in/v1/order/create";
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl))
                            .header("Content-Type",APP_JSON)
                            .header("Accept",APP_JSON)
                            .header("Authorization","Bearer "+tokenCache.get("testKey1"))
                            .header("X-API-VERSION","1.0")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                            .build() ;
                    return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
                })
                .flatMap(response -> {
                    if (response == null || response.body() == null) {
                        return Mono.error(new IOException("Empty response body"));
                    }
                    log.info("Response for place order from groww : {}", response.body());
                    try {
                        OrderResponse orderResponse = mapper.readValue(response.body(), OrderResponse.class);
                        return Mono.just(orderResponse);
                    } catch (Exception e) {
                        return Mono.error(new IOException("Failed to parse order response", e));
                    }
                });
    }

    public Mono<String> closeOrder(String growwOrderId) {
        return Mono.fromCallable(() -> {
            Map<String, String> cancelOrderRequest = new LinkedHashMap<>();
            cancelOrderRequest.put("segment", "FNO");
            cancelOrderRequest.put("groww_order_id", growwOrderId);
            return mapper.writeValueAsString(cancelOrderRequest);
        })
        .flatMap(jsonBody -> {
            String baseUrl = "https://api.groww.in/v1/order/cancel";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Content-Type",APP_JSON)
                    .header("Accept",APP_JSON)
                    .header("Authorization","Bearer "+tokenCache.get("testKey1"))
                    .header("X-API-VERSION","1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build() ;
            return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        })
        .flatMap(response -> {
            if (response == null || response.body() == null) {
                return Mono.error(new IOException("Empty response body"));
            }
            log.info("Response for cancel order from groww : {}", response.body());
            return Mono.just(response.body());
        });
    }

    public Mono<String> modifyOrder(String jsonBody) {
        String baseUrl = "https://api.groww.in/v1/order/modify";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Content-Type",APP_JSON)
                .header("Accept",APP_JSON)
                .header("Authorization","Bearer "+tokenCache.get("testKey1"))
                .header("X-API-VERSION","1.0")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build() ;
        return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .flatMap(response -> {
                    if (response == null || response.body() == null) {
                        return Mono.error(new IOException("Empty response body"));
                    }
                    return Mono.just(response.body());
                });
    }

    public Mono<String> getBalance() {
        String baseUrl = "https://api.groww.in/v1/margins/detail/user";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Accept",APP_JSON)
                .header("Authorization","Bearer "+tokenCache.get("testKey1"))
                .header("X-API-VERSION","1.0")
                .GET()
                .build() ;
        return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .flatMap(response -> {
                    if (response == null || response.body() == null) {
                        return Mono.error(new IOException("Empty response body"));
                    }
                    return Mono.just(response.body());
                });
    }

    public Mono<Map<String, Double>> getLiveLtp(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Mono.just(Map.of());
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

        List<Mono<Map<String, Double>>> requests = new ArrayList<>();
        if (!fnoSymbols.isEmpty()) {
            requests.add(fetchLtpForSegment("FNO", fnoSymbols));
        }
        if (!cashSymbols.isEmpty()) {
            requests.add(fetchLtpForSegment("CASH", cashSymbols));
        }

        return Flux.merge(requests)
                .reduce(new HashMap<String, Double>(), (acc, map) -> {
                    acc.putAll(map);
                    return acc;
                });
    }

    private Mono<Map<String, Double>> fetchLtpForSegment(String segment, List<String> symbols) {
        // Format each symbol with NSE_ prefix if not already prefixed
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

        return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .flatMap(response -> {
                    if (response == null || response.body() == null) {
                        log.error("[Groww-LTP] Empty response for segment={}, symbols={}", segment, symbolsParam);
                        return Mono.just(Map.<String, Double>of());
                    }
                    log.info("[Groww-LTP] Raw response for segment={}, status={}: {}", segment, response.statusCode(), response.body());
                    try {
                        LtpResponse ltpResponse = mapper.readValue(response.body(), LtpResponse.class);
                        Map<String, Double> rawPayload = ltpResponse.getPayload();
                        if (rawPayload == null || rawPayload.isEmpty()) {
                            log.warn("[Groww-LTP] No LTP payload returned for segment={}, symbols={}", segment, symbolsParam);
                            return Mono.just(Map.<String, Double>of());
                        }

                        Map<String, Double> normalized = new HashMap<>();
                        for (Map.Entry<String, Double> entry : rawPayload.entrySet()) {
                            String key = entry.getKey();
                            Double price = entry.getValue();
                            if (key != null && price != null) {
                                normalized.put(key, price);
                                // Strip NSE_ or BSE_ or NSE: or BSE: prefix so activeSymbols key matches directly
                                String clean = key.replaceAll("^(NSE|BSE)[_:]", "");
                                normalized.put(clean, price);
                                // Also map back to original symbols passed by user
                                for (String orig : symbols) {
                                    if (orig.equalsIgnoreCase(clean) || orig.equalsIgnoreCase(key)) {
                                        normalized.put(orig, price);
                                    }
                                }
                            }
                        }
                        log.info("[Groww-LTP] Normalized LTP results for segment {}: {}", segment, normalized);
                        return Mono.just(normalized);
                    } catch (Exception e) {
                        log.error("[Groww-LTP] Failed to parse LTP response for segment={}: {}", segment, response.body(), e);
                        return Mono.just(Map.<String, Double>of());
                    }
                })
                .onErrorResume(e -> {
                    log.error("[Groww-LTP] Request failed for segment={}, symbols={}: {}", segment, symbolsParam, e.getMessage());
                    return Mono.just(Map.<String, Double>of());
                });
    }
}
