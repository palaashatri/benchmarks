package com.palaashatri.bench.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Common HTTP control-plane contracts, with bounded input and request timeouts. */
public final class Routes {
    private Routes() { }
    public static HttpHandler guard(HttpHandler handler) {
        return exchange -> {
            try { handler.handle(exchange); }
            catch (IllegalArgumentException invalid) { Json.reply(exchange, 400, Map.of("error", "invalid_request")); }
            finally { exchange.close(); }
        };
    }
    public static boolean method(HttpExchange exchange, String method) throws IOException {
        if (method.equals(exchange.getRequestMethod())) return true;
        Json.reply(exchange, 405, Map.of("error", "method_not_allowed")); return false;
    }
    public static HttpResponse<String> call(HttpClient client, int port, String method, String path, String body) throws IOException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        try { return client.send(request, HttpResponse.BodyHandlers.ofString()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("interrupted", interrupted); }
    }
}
