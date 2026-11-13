package com.vnkst.closing.config;

import com.vnkst.closing.entity.Tokens;
import com.vnkst.closing.repository.TokenRepository;
import com.vnkst.closing.service.TokenCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class TokenConfig {

    private final TokenRepository accessTokenRepo;
    private final TokenCache tokenCache;

    public void loadTokensFromDb() {
        try {
            List<Tokens> fromDb = accessTokenRepo.findAll();
            if (fromDb == null || fromDb.isEmpty()) {
                log.warn("No tokens found in database during startup.");
                return;
            }
            tokenCache.clear();
            for (Tokens token : fromDb) {
                tokenCache.put(token.getTokenName(), token.getToken());
                tokenCache.addToken(token.getToken());
            }
            log.info("Loaded {} tokens from database into cache (expiry: {})",
                    fromDb.size(), fromDb.getFirst().getExpiry());
        } catch (Exception e) {
            log.error("Failed to load tokens from database during startup: {}", e.getMessage(), e);
        }
    }
}

