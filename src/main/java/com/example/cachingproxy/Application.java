package com.example.cachingproxy;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration.class)
public class Application {
    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.help()) {
                System.out.println("Usage: caching-proxy --port <1-65535> --origin <http(s)://host>\n"
                    + "       caching-proxy --clear-cache [--port <number>] (default: 3000)");
                return;
            }
            if (options.clear()) {
                var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + options.port() + "/__caching_proxy/cache"))
                    .timeout(Duration.ofSeconds(5)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IllegalArgumentException("Cache clear failed: HTTP " + response.statusCode());
                System.out.println(response.body());
                return;
            }
            SpringApplication.run(Application.class, "--server.port=" + options.port(),
                "--origin=" + options.origin(), "--spring.mvc.dispatch-options-request=true",
                "--spring.mvc.dispatch-trace-request=true");
        } catch (Exception error) {
            System.err.println("caching-proxy: " + error.getMessage());
            System.exit(1);
        }
    }

    record Options(int port, String origin, boolean clear, boolean help) {
        static Options parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            boolean clear = false;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--help") || arg.equals("-h")) return new Options(3000, null, false, true);
                if (arg.equals("--clear-cache")) { clear = true; continue; }
                String[] pair = arg.split("=", 2);
                if (!Set.of("--port", "--origin").contains(pair[0])) throw new IllegalArgumentException("Unknown option: " + pair[0]);
                String value;
                if (pair.length == 2) value = pair[1];
                else if (++i < args.length && !args[i].startsWith("--")) value = args[i];
                else throw new IllegalArgumentException("Missing value for " + pair[0]);
                if (values.put(pair[0], value) != null) throw new IllegalArgumentException("Duplicate option: " + pair[0]);
            }
            int port;
            try { port = Integer.parseInt(values.getOrDefault("--port", "3000")); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Port must be a number between 1 and 65535"); }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");
            String origin = values.get("--origin");
            if (clear) {
                if (origin != null) throw new IllegalArgumentException("--origin cannot be used with --clear-cache");
            } else {
                if (origin == null || !values.containsKey("--port")) throw new IllegalArgumentException("--port and --origin are required (see --help)");
                URI uri = URI.create(origin);
                if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() > 65535 || uri.getPort() == 0)
                    throw new IllegalArgumentException("Origin must be an HTTP(S) URL without credentials, query, or fragment");
                origin = origin.replaceAll("/+$", "");
            }
            return new Options(port, origin, clear, false);
        }
    }
}
