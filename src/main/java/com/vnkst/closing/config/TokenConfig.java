package com.vnkst.closing.config;

import com.vnkst.closing.entity.AccessToken;
import com.vnkst.closing.entity.Tokens;
import com.vnkst.closing.repository.TokenRepository;
import com.vnkst.closing.service.TokenCache;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.vnkst.closing.constants.CommonConstants.APP_JSON;
import static com.vnkst.closing.utils.ChecksumGenerator.generateChecksum;
import static com.vnkst.closing.utils.ChecksumGenerator.getLatestTimestampinEpochSeconds;

@Component
@RequiredArgsConstructor
@Slf4j
public class TokenConfig {

    private static final ZoneId KOLKATA_ZONE = ZoneId.of("Asia/Kolkata");

    @Value("${groww_secret:}")
    private String apiSecret;

    @Value("${groww_api_key:}")
    private String apiKey;

    private final TokenRepository accessTokenRepo;
    private final TokenCache tokenCache;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    @PostConstruct
    public void generateToken() {
        try {
            List<Tokens> fromDb = accessTokenRepo.findAll();
            LocalDateTime now = LocalDateTime.now(KOLKATA_ZONE);
            if (fromDb == null || fromDb.isEmpty()) {
                generateAndSaveToDB();
            } else {
                LocalDateTime expiry = fromDb.getFirst().getExpiry();
                if (expiry != null && expiry.isAfter(now)) {
                    log.info("Tokens are valid (expiry: {}), loading from database", expiry);
                    for (Tokens token : fromDb) {
                        tokenCache.put(token.getTokenName(), token.getToken());
                        tokenCache.addToken(token.getToken());
                    }
                } else {
                    log.warn("Tokens expired (expiry: {}), regenerating...", expiry);
                    accessTokenRepo.deleteAll();
                    generateAndSaveToDB();
                }
            }
        } catch (Exception e) {
            log.error("Token generation/loading failed during startup: {}. Please verify your Groww API credentials.",
                    e.getMessage(), e);
        }
    }

    private void generateAndSaveToDB() throws IOException, InterruptedException {
        log.info("Generating token from Groww API...");
        String timestamp = getLatestTimestampinEpochSeconds();
        String checkSum = generateChecksum(apiSecret, timestamp);
        AccessToken tokenFromGroww = generateToken(checkSum, timestamp);

        if (tokenFromGroww == null || tokenFromGroww.getToken() == null || tokenFromGroww.getToken().isBlank()) {
            throw new IOException("Token was not generated. Please provide a valid Groww API key and secret.");
        }

        log.info("Successfully generated Groww token. Populating testKey1 through testKey5...");
        List<Tokens> tokens = new ArrayList<>();
        LocalDateTime expiry = tokenFromGroww.getExpiry() != null
                ? tokenFromGroww.getExpiry().withHour(6).withMinute(0).withSecond(0)
                : LocalDateTime.now(KOLKATA_ZONE).plusDays(1).withHour(6).withMinute(0).withSecond(0);

        for (int i = 0; i <= 4; i++) {
            Tokens token = new Tokens();
            token.setTokenRefId(tokenFromGroww.getTokenRefId() != null ? tokenFromGroww.getTokenRefId() + "_" + (i + 1)
                    : "tokenRef_" + (i + 1));
            String sessionName = tokenFromGroww.getSessionName() != null
                    ? tokenFromGroww.getSessionName() + "_" + (i + 1)
                    : "session_" + (i + 1);
            token.setSessionName(sessionName);
            String tokenKey = "testKey" + (i + 1);
            token.setTokenName(tokenKey);
            token.setExpiry(expiry);
            token.setToken(tokenFromGroww.getToken());
            token.setActive(tokenFromGroww.isActive());
            token.setNew(true);
            tokens.add(token);

            tokenCache.put(tokenKey, tokenFromGroww.getToken());
            tokenCache.addToken(tokenFromGroww.getToken());
        }

        accessTokenRepo.deleteAll();
        accessTokenRepo.saveAll(tokens);
        log.info("Successfully saved {} token entries to database", tokens.size());
    }

    private AccessToken generateToken(String checksum, String timestamp) throws IOException, InterruptedException {
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
        if (response == null || response.body() == null) {
            throw new IOException("Empty response from Groww token API");
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
                throw new IOException("Invalid response status: missing token in body: " + response.body());
            }
            return accessToken;
        } catch (Exception e) {
            log.error("Failed to parse Groww token response: {}", response.body(), e);
            throw new IOException("Failed to parse market data", e);
        }
    }
}

