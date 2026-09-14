# Cache Forward

A Java CLI that forwards HTTP requests to an origin and serves repeated GET requests from an in-memory cache. Responses include `X-Cache: MISS` when forwarded and `X-Cache: HIT` when cached.

## Build and run

Requires Java 17 or newer and Maven 3.6.3 or newer.

```sh
mvn package
chmod +x caching-proxy
./caching-proxy --port 3000 --origin http://dummyjson.com
```

To use the command without `./`, add this project directory to your PATH:

```sh
export PATH="$PWD:$PATH"
caching-proxy --port 3000 --origin http://dummyjson.com
```

The executable JAR also accepts the same options:

```sh
java -jar target/caching-proxy-0.0.1-SNAPSHOT.jar --port 3000 --origin http://dummyjson.com
```

Both `--port 3000` and `--port=3000` forms work. Use `--help` for usage. Invalid options exit with a nonzero status.

## Try the cache

In another terminal:

```sh
curl -i http://localhost:3000/products
curl -i http://localhost:3000/products
```

For a cacheable response, the first request returns `X-Cache: MISS`; the second returns `X-Cache: HIT`. Status codes, end-to-end response headers, and binary bodies are preserved. Paths and encoded query strings are appended to the origin; an origin such as `https://example.com/api` forwards `/products` to `/api/products`.

## Clear the running server's cache

```sh
./caching-proxy --clear-cache
# For a server on another port:
./caching-proxy --clear-cache --port 8080
```

The default clear-cache port is 3000. The command contacts the running proxy, prints `Cache cleared`, and exits. If the server is unavailable, it exits with an error. The next cacheable request returns `MISS`.

Cache clearing uses the reserved `DELETE /__caching_proxy/cache` endpoint, accessible only from loopback addresses. Run the proxy directly; do not expose this management route through another reverse proxy that makes external traffic appear local. All other routes are forwarded.

## Cache behavior

- Caches successful (200) GET responses for up to 60 seconds, respecting shorter `max-age`/`s-maxage` values and upstream `Age`.
- Holds at most 1,000 entries and 32 MiB of response bodies, evicting least recently used entries. Header and key memory is additional; requests and origin responses are buffered in memory.
- Separates cache entries by URL, query string, and forwarded request headers.
- Bypasses storage for requests with bodies, authorization, cookies, ranges, conditional headers, or cache-bypass directives. Responses with `private`, `no-store`, `no-cache`, `Set-Cookie`, `Vary: *`, or `Expires` are conservatively not cached.
- Forwards other methods and origin errors without caching. Successful mutation requests invalidate the cache.
- Removes hop-by-hop headers in both directions, including headers named by `Connection`. Redirects are returned to the caller without following them.
- Returns 502 for origin connection failures and 504 for timeouts (5-second connection timeout; 30-second request timeout).
- Cache is local to each process and disappears on restart. Concurrent misses may each contact the origin. Clearing prevents older in-flight responses from repopulating the cache.

This is a small buffered HTTP proxy, not a streaming or WebSocket proxy. It uses Spring Boot and the JDK HTTP client, with no external cache service.

## Tests

```sh
mvn test
```

Tests start a local origin and proxy and verify binary bodies, header preservation, HIT/MISS behavior and origin request counts, query/header isolation, error and redirect forwarding, mutation forwarding and invalidation, privacy bypasses, expiry, CLI clearing, argument validation, and cache bounds.
