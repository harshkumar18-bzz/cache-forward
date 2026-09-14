package com.example.cachingproxy.controller;

import com.example.cachingproxy.model.CacheEntry;
import com.example.cachingproxy.service.CacheService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProxyController {
    private final CacheService cache;
    private final String origin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Set<String> HOP_HEADERS = Set.of("connection", "keep-alive", "proxy-authenticate",
        "proxy-authorization", "te", "trailer", "trailers", "transfer-encoding", "upgrade");

    public ProxyController(CacheService cache, @Value("${origin}") String origin) {
        this.cache = cache;
        this.origin = origin;
    }

    @DeleteMapping("/__caching_proxy/cache")
    public ResponseEntity<String> clear(HttpServletRequest request) throws UnknownHostException {
        if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress())
            return ResponseEntity.status(403).body("Cache clearing is only available locally");
        cache.clear();
        return ResponseEntity.ok("Cache cleared");
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        String url = origin + request.getRequestURI()
            + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        HttpHeaders incoming = new HttpHeaders();
        Collections.list(request.getHeaderNames()).forEach(name -> incoming.put(name, Collections.list(request.getHeaders(name))));
        HttpHeaders forwarded = filter(incoming);
        for (String name : List.of("host", "content-length", "expect")) forwarded.remove(name);
        // Include every forwarded header so negotiated representations never share a cache entry.
        Map<String, List<String>> normalized = new TreeMap<>();
        forwarded.forEach((name, values) -> normalized.put(name.toLowerCase(Locale.ROOT), values));
        StringBuilder keyBuilder = new StringBuilder(request.getMethod()).append(' ').append(url).append(' ');
        normalized.forEach((name, values) -> {
            keyBuilder.append(name.length()).append(':').append(name).append(':').append(values.size()).append(':');
            values.forEach(value -> keyBuilder.append(value.length()).append(':').append(value));
        });
        String key = keyBuilder.toString();
        boolean eligible = request.getMethod().equals("GET") && (body == null || body.length == 0)
            && !incoming.containsKey("Authorization") && !incoming.containsKey("Cookie")
            && !incoming.containsKey("Range") && incoming.keySet().stream().noneMatch(n -> n.toLowerCase(Locale.ROOT).startsWith("if-"))
            && !bypass(incoming) && !"no-cache".equalsIgnoreCase(incoming.getFirst("Pragma"));
        long generation = cache.generation();
        if (eligible) {
            var entry = cache.get(key);
            if (entry.isPresent()) return response(entry.get(), "HIT");
        }
        try {
            var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .method(request.getMethod(), body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            forwarded.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            var upstream = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            HttpHeaders headers = new HttpHeaders();
            upstream.headers().map().forEach(headers::put);
            headers = filter(headers);
            CacheEntry entry = new CacheEntry(upstream.statusCode(), headers, upstream.body());
            if (eligible && upstream.statusCode() == 200 && !bypass(headers)
                && !headers.containsKey("Set-Cookie") && !headers.getOrEmpty("Vary").stream().anyMatch(v -> Arrays.asList(v.split("\\s*,\\s*")).contains("*"))) {
                cache.put(key, entry, generation, maxAge(headers));
            }
            if (!Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod()) && upstream.statusCode() < 400) cache.clear();
            return response(entry, "MISS");
        } catch (HttpTimeoutException error) {
            return failure(504, "Origin request timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failure(502, "Origin request interrupted");
        } catch (IOException | IllegalArgumentException error) {
            return failure(502, "Unable to reach origin server");
        }
    }

    private static boolean bypass(HttpHeaders headers) {
        String control = String.join(",", headers.getOrEmpty("Cache-Control")).toLowerCase(Locale.ROOT);
        return control.contains("no-store") || control.contains("private") || control.contains("no-cache") || control.contains("max-age=0");
    }

    private static long maxAge(HttpHeaders headers) {
        long seconds = 60;
        // Expires-only policies are conservatively forwarded without storage.
        if (headers.containsKey("Expires")) return 0;
        for (String part : String.join(",", headers.getOrEmpty("Cache-Control")).split(",")) {
            String[] directive = part.trim().split("=", 2);
            if (directive[0].equalsIgnoreCase("max-age") || directive[0].equalsIgnoreCase("s-maxage")) {
                try { seconds = Math.min(seconds, Long.parseLong(directive[1].replace("\"", ""))); }
                catch (RuntimeException error) { return 0; }
            }
        }
        try { return Math.max(0, seconds - Math.max(0, Long.parseLong(headers.getFirst("Age") == null ? "0" : headers.getFirst("Age")))); }
        catch (NumberFormatException error) { return 0; }
    }

    private static HttpHeaders filter(HttpHeaders source) {
        Set<String> excluded = new HashSet<>(HOP_HEADERS);
        source.getOrEmpty("Connection").forEach(value -> Arrays.stream(value.split(","))
            .forEach(name -> excluded.add(name.trim().toLowerCase(Locale.ROOT))));
        HttpHeaders result = new HttpHeaders();
        source.forEach((name, values) -> {
            if (!excluded.contains(name.toLowerCase(Locale.ROOT))) result.put(name, new ArrayList<>(values));
        });
        return result;
    }

    private static ResponseEntity<byte[]> response(CacheEntry entry, String state) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(entry.headers());
        headers.set("X-Cache", state);
        return new ResponseEntity<>(entry.body(), headers, HttpStatusCode.valueOf(entry.status()));
    }

    private static ResponseEntity<byte[]> failure(int status, String message) {
        return ResponseEntity.status(status).header("X-Cache", "MISS").contentType(MediaType.TEXT_PLAIN)
            .body(message.getBytes(StandardCharsets.UTF_8));
    }
}
