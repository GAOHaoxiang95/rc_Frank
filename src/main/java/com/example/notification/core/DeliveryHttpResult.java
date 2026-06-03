package com.example.notification.core;

import java.net.http.HttpHeaders;

public record DeliveryHttpResult(
        int statusCode,
        HttpHeaders headers,
        String body
) {
}
