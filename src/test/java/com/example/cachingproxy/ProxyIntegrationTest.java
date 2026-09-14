package com.example.cachingproxy;

import com.example.cachingproxy.model.CacheEntry;
import com.example.cachingproxy.service.CacheService;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.HttpHeaders;
import static org.junit.jupiter.api.Assertions.*;

class ProxyIntegrationTest {
    static HttpServer origin;
    static ServletWebServerApplicationContext app;
    static String base;
    static final AtomicInteger calls = new AtomicInteger();
    static final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll static void start() throws Exception {
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> {
            calls.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            byte[] body = path.equals("/binary") ? new byte[]{0, -1, 1, -128} :
                (exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                + exchange.getRequestHeaders().getFirst("Accept-Language") + " "
                + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Origin", "preserved");
            if (path.equals("/private")) exchange.getResponseHeaders().add("Cache-Control", "no-store");
            if (path.equals("/cookie")) exchange.getResponseHeaders().add("Set-Cookie", "session=one");
            if (path.equals("/short")) exchange.getResponseHeaders().add("Cache-Control", "max-age=1");
            if (path.equals("/redirect")) exchange.getResponseHeaders().add("Location", "/binary");
            exchange.sendResponseHeaders(path.equals("/error") ? 404 : path.equals("/redirect") ? 302 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        origin.start();
        app = (ServletWebServerApplicationContext) SpringApplication.run(Application.class,
            "--server.port=0", "--origin=http://127.0.0.1:" + origin.getAddress().getPort(), "--spring.main.banner-mode=off");
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
    }
    @AfterAll static void stop() { if (app != null) app.close(); if (origin != null) origin.stop(0); }
    @BeforeEach void reset() { app.getBean(CacheService.class).clear(); calls.set(0); }
    static HttpResponse<byte[]> request(String path, String method, String body, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).method(method,
            body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) builder.headers(headers);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    static String state(HttpResponse<?> response) { return response.headers().firstValue("X-Cache").orElseThrow(); }

    @Test void cachesBinaryAndHeaders() throws Exception {
        var first = request("/binary", "GET", null);
        var second = request("/binary", "GET", null);
        assertEquals("MISS", state(first)); assertEquals("HIT", state(second)); assertEquals(1, calls.get());
        assertArrayEquals(new byte[]{0, -1, 1, -128}, second.body());
        assertEquals("preserved", second.headers().firstValue("X-Origin").orElseThrow());
    }
    @Test void separatesQueriesAndRepresentations() throws Exception {
        request("/search?q=a%20b", "GET", null, "Accept-Language", "en");
        var french = request("/search?q=a%20b", "GET", null, "Accept-Language", "fr");
        request("/search?q=c", "GET", null, "Accept-Language", "en");
        assertEquals("MISS", state(french)); assertTrue(new String(french.body()).contains("q=a%20b"));
        assertEquals("HIT", state(request("/search?q=a%20b", "GET", null, "Accept-Language", "en")));
        assertEquals(3, calls.get());
    }
    @Test void forwardsErrorsRedirectsAndMutations() throws Exception {
        assertEquals(404, request("/error", "GET", null).statusCode());
        assertEquals(302, request("/redirect", "GET", null).statusCode());
        for (int i = 0; i < 2; i++) {
            var response = request("/echo", "PATCH", "payload");
            assertEquals("MISS", state(response)); assertTrue(new String(response.body()).contains("payload"));
        }
        assertEquals(4, calls.get());
    }
    @Test void bypassesPrivateResponsesAndRequests() throws Exception {
        for (String path : new String[]{"/private", "/cookie"}) {
            request(path, "GET", null); assertEquals("MISS", state(request(path, "GET", null)));
        }
        for (String header : new String[]{"Authorization", "Cookie"}) {
            request("/personal", "GET", null, header, "secret");
            assertEquals("MISS", state(request("/personal", "GET", null, header, "secret")));
        }
        assertEquals(8, calls.get());
    }
    @Test void clearCommandClearsRunningServer() throws Exception {
        request("/binary", "GET", null);
        Application.main(new String[]{"--clear-cache", "--port", Integer.toString(app.getWebServer().getPort())});
        assertEquals("MISS", state(request("/binary", "GET", null))); assertEquals(2, calls.get());
    }
    @Test void expiresEntries() throws Exception {
        request("/short", "GET", null);
        Thread.sleep(1100);
        assertEquals("MISS", state(request("/short", "GET", null)));
    }
    @Test void invalidatesAfterMutation() throws Exception {
        request("/binary", "GET", null); request("/echo", "POST", "update");
        assertEquals("MISS", state(request("/binary", "GET", null)));
    }
    @Test void reportsUnavailableOrigin() throws Exception {
        int unusedPort;
        try (var socket = new java.net.ServerSocket(0)) { unusedPort = socket.getLocalPort(); }
        var controller = new com.example.cachingproxy.controller.ProxyController(new CacheService(), "http://127.0.0.1:" + unusedPort);
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/products");
        var response = controller.proxy(request, null);
        assertEquals(502, response.getStatusCode().value());
        assertEquals("MISS", response.getHeaders().getFirst("X-Cache"));
    }
    @Test void boundsCacheAndRejectsWritesFromBeforeClear() {
        CacheService cache = new CacheService();
        var entry = new CacheEntry(200, new HttpHeaders(), new byte[]{1});
        long generation = cache.generation(); cache.clear(); cache.put("old", entry, generation, 60);
        assertEquals(0, cache.size());
        for (int i = 0; i < 1001; i++) cache.put("key" + i, entry, cache.generation(), 60);
        assertEquals(1000, cache.size()); assertTrue(cache.get("key0").isEmpty());
    }
    @Test void validatesCli() {
        assertEquals(3000, Application.Options.parse(new String[]{"--clear-cache"}).port());
        assertEquals("https://example.com/api", Application.Options.parse(new String[]{"--port=3000", "--origin=https://example.com/api/"}).origin());
        for (String[] args : new String[][]{{}, {"--port", "bad"}, {"--unknown"}, {"--origin"},
            {"--port", "0", "--origin", "http://example.com"}, {"--port", "3000", "--origin", "file:///tmp"}})
            assertThrows(IllegalArgumentException.class, () -> Application.Options.parse(args));
    }
}
