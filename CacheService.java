package com.example.cachingproxy.service;

import com.example.cachingproxy.model.CacheEntry;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CacheService {
    private static final int MAX_ENTRIES = 1000;
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final long TTL_NANOS = 60_000_000_000L;
    private record Stored(CacheEntry entry, long expires) {}
    private final LinkedHashMap<String, Stored> cache = new LinkedHashMap<>(16, .75f, true);
    private long bytes;
    private long generation;

    public synchronized Optional<CacheEntry> get(String key) {
        Stored stored = cache.get(key);
        if (stored == null) return Optional.empty();
        if (System.nanoTime() - stored.expires() >= 0) {
            bytes -= cache.remove(key).entry().body().length;
            return Optional.empty();
        }
        return Optional.of(stored.entry());
    }

    public synchronized long generation() { return generation; }

    public synchronized void put(String key, CacheEntry entry, long expectedGeneration, long maxAgeSeconds) {
        if (generation != expectedGeneration || entry.body().length > MAX_BYTES || maxAgeSeconds <= 0) return;
        Stored previous = cache.remove(key);
        if (previous != null) bytes -= previous.entry().body().length;
        long ttl = Math.min(60, maxAgeSeconds) * (TTL_NANOS / 60);
        cache.put(key, new Stored(entry, System.nanoTime() + ttl));
        bytes += entry.body().length;
        while (cache.size() > MAX_ENTRIES || bytes > MAX_BYTES) {
            var iterator = cache.entrySet().iterator();
            bytes -= iterator.next().getValue().entry().body().length;
            iterator.remove();
        }
    }

    public synchronized void clear() { cache.clear(); bytes = 0; generation++; }
    public synchronized int size() { return cache.size(); }
}
