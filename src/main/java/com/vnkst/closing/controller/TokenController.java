package com.vnkst.closing.controller;

import com.vnkst.closing.config.TokenConfig;
import com.vnkst.closing.service.TokenCache;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*")
public class TokenController {

    private final TokenConfig tokenConfig;
    private final TokenCache tokenCache;

    @RequestMapping(value = "/tokens/refresh", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<Map<String, Object>> refreshTokens() {
        tokenConfig.loadTokensFromDb();
        int count = tokenCache.getTokens().size();
        if (count == 0) {
            return ResponseEntity.ok(Map.of(
                    "status", "EMPTY",
                    "count", 0,
                    "message", "No tokens found in database."
            ));
        }
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "count", count,
                "message", "Loaded " + count + " tokens into closing cache."
        ));
    }
}

