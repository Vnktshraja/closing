package com.vnkst.closing.service;

import lombok.ToString;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ToString
public class TokenCache {

    private final Map<String, String> tokenMap = new ConcurrentHashMap<>();
    private final List<String> tokenList = Collections.synchronizedList(new ArrayList<>());

    public void addToken(String token) {
        tokenList.add(token);
    }

    public List<String> getTokens() {
        synchronized (tokenList) {
            return List.copyOf(tokenList);
        }
    }

    public void put(String key, String value) {
        tokenMap.put(key, value);
    }

    public String get(String key) {
        return tokenMap.get(key);
    }

    public Map<String, String> getAll() {
        return Map.copyOf(tokenMap);
    }

    public void remove(String key) {
        tokenMap.remove(key);
    }

    public boolean contains(String key) {
        return tokenMap.containsKey(key);
    }

    public void clear() {
        tokenMap.clear();
        tokenList.clear();

    }

}