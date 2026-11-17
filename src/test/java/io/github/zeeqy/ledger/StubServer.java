package io.github.zeeqy.ledger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Tiny local HTTP server standing in for a merchant webhook endpoint or the fraud model. */
public final class StubServer implements AutoCloseable {

    public record Received(String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst())
                    .findFirst()
                    .orElse(null);
        }
    }

    public record Reply(int status, String body) {}

    private final HttpServer server;
    public final List<Received> received = new CopyOnWriteArrayList<>();

    public StubServer(Function<Received, Reply> handler) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> handle(exchange, handler));
        server.start();
    }

    private void handle(HttpExchange ex, Function<Received, Reply> handler) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Received r = new Received(ex.getRequestURI().getPath(), Map.copyOf(ex.getRequestHeaders()), body);
        received.add(r);
        Reply reply = handler.apply(r);
        byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            ex.getResponseBody().write(out);
        }
        ex.close();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
