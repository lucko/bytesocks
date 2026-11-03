package me.lucko.bytesocks;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeIT {

    private static final String BASE_URL = System.getProperty("bytesocks.url", "http://localhost:8080").replaceAll("/+$", "");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void healthEndpointIsOk() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(BASE_URL + "/health")).GET());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"ok\""), response.body());
    }

    @Test
    void createChannel() throws Exception {
        HttpResponse<String> response = requestChannel();
        assertEquals(201, response.statusCode(), response.body());
        String key = channelKey(response.body());
        assertEquals(key, response.headers().firstValue("Location").orElse(""), response.headers().toString());
    }

    @Test
    void websocketForwardsMessageToAnotherClient() throws Exception {
        HttpResponse<String> response = requestChannel();
        assertEquals(201, response.statusCode(), response.body());
        String key = channelKey(response.body());

        URI uri = URI.create(BASE_URL.replaceFirst("^http", "ws") + "/" + key);
        MessageListener receiver = new MessageListener();
        WebSocket sender = null;
        WebSocket recipient = null;

        try {
            sender = connect(uri, new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                }
            });
            recipient = connect(uri, receiver);

            String message = "bytesocks smoke test " + System.nanoTime();
            sender.sendText(message, true).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertEquals(message, receiver.message.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS));

        } finally {
            if (sender != null) {
                sender.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
            if (recipient != null) {
                recipient.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
        }
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return CLIENT.send(request.header("User-Agent", "bytesocks-smoke-test").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> requestChannel() throws Exception {
        return send(HttpRequest.newBuilder(URI.create(BASE_URL + "/create")).GET());
    }

    private static String channelKey(String body) {
        Matcher matcher = Pattern.compile("\"key\"\\s*:\\s*\"([a-zA-Z0-9]+)\"").matcher(body);
        assertTrue(matcher.find(), "no channel key in response: " + body);
        return matcher.group(1);
    }

    private static WebSocket connect(URI uri, WebSocket.Listener listener) throws Exception {
        return CLIENT.newWebSocketBuilder()
                .header("User-Agent", "bytesocks-smoke-test")
                .buildAsync(uri, listener)
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    private static final class MessageListener implements WebSocket.Listener {
        private final CompletableFuture<String> message = new CompletableFuture<>();
        private final StringBuilder content = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            this.content.append(data);
            if (last) {
                this.message.complete(this.content.toString());
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            this.message.completeExceptionally(error);
        }
    }
}
