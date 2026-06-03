package com.example.notification.core;

import com.example.notification.config.NotificationProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DeliveryHttpClient {

    private static final Set<String> SKIPPED_HEADERS = Set.of(
            "host",
            "content-length",
            "connection",
            "transfer-encoding",
            "expect",
            "upgrade",
            "x-idempotency-key");

    private final HttpClient httpClient;
    private final NotificationProperties properties;

    public DeliveryHttpClient(HttpClient httpClient, NotificationProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    public DeliveryHttpResult send(NotificationTask task, URI uri) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(properties.getDelivery().getRequestTimeout());

        for (Map.Entry<String, String> header : task.headers().entrySet()) {
            String headerName = header.getKey().toLowerCase(Locale.ROOT);
            if (!SKIPPED_HEADERS.contains(headerName)) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        builder.header("X-Idempotency-Key", task.idempotencyKey());

        HttpRequest.BodyPublisher bodyPublisher = task.body().isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(task.body(), StandardCharsets.UTF_8);
        HttpRequest request = builder.method(task.method(), bodyPublisher).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new DeliveryHttpResult(response.statusCode(), response.headers(), response.body());
    }
}
