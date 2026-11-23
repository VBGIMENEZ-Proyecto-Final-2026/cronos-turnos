package ar.edu.um.cronos;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Un servidor HTTP de mentira para probar los clientes REST contra HTTP real (cabeceras, timeouts, redirecciones,
 * reintentos), sin red externa. Responde con lo que se le encola y, si se acaba la cola, repite la última respuesta.
 * Guarda cada pedido que recibe.
 */
public final class StubServer implements AutoCloseable {

    /** Un pedido recibido. */
    public record Request(String method, String uri, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            for (var entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return String.join(",", entry.getValue());
                }
            }
            return null;
        }
    }

    /** Una respuesta que el servidor va a dar. */
    public record Reply(int status, String contentType, String body, long delayMillis, String location) {

        public static Reply json(int status, String body) {
            return new Reply(status, "application/json", body, 0, null);
        }

        public static Reply problem(int status, String code) {
            return new Reply(status, "application/problem+json", "{\"status\":" + status + ",\"code\":\"" + code
                    + "\",\"detail\":\"x\"}", 0, null);
        }

        public static Reply redirect(String location) {
            return new Reply(302, "text/plain", "", 0, location);
        }

        public Reply delayed(long millis) {
            return new Reply(status, contentType, body, millis, location);
        }
    }

    private final HttpServer server;
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Reply last = Reply.json(200, "{}");

    public StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Encola respuestas: se dan en orden y la última se repite. */
    public StubServer reply(Reply... next) {
        for (Reply reply : next) {
            replies.add(reply);
        }
        return this;
    }

    /** Olvida los pedidos y las respuestas encoladas, para reutilizar el servidor entre pruebas. */
    public void reset() {
        replies.clear();
        requests.clear();
        last = Reply.json(200, "{}");
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                Map.copyOf(exchange.getRequestHeaders()), requestBody));
        Reply reply = replies.poll();
        if (reply == null) {
            reply = last;
        } else {
            last = reply;
        }
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", reply.contentType());
        if (reply.location() != null) {
            exchange.getResponseHeaders().add("Location", reply.location());
        }
        try {
            exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (IOException ignored) {
            // el cliente se cansó de esperar (timeout): es lo que algunas pruebas provocan
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
