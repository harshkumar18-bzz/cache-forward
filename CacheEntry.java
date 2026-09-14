package com.example.cachingproxy.model;

import org.springframework.http.HttpHeaders;

public record CacheEntry(int status, HttpHeaders headers, byte[] body) {}
