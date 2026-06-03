package com.example.notification.security;

import com.example.notification.config.NotificationProperties;
import com.example.notification.core.BadRequestException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TargetUrlPolicy {

    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final NotificationProperties properties;

    public TargetUrlPolicy(NotificationProperties properties) {
        this.properties = properties;
    }

    public URI validate(String sourceSystem, String targetUrl) {
        URI uri = parse(targetUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            if (!properties.getSecurity().isAllowLocalTargets()) {
                throw new BadRequestException("targetUrl must use https");
            }
            if (!"http".equalsIgnoreCase(uri.getScheme())) {
                throw new BadRequestException("targetUrl must use https");
            }
        }
        if (uri.getRawUserInfo() != null) {
            throw new BadRequestException("targetUrl must not contain user info");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BadRequestException("targetUrl host is required");
        }
        if (isIpLiteral(host) && !properties.getSecurity().isAllowLocalTargets()) {
            throw new BadRequestException("targetUrl must use an allowed domain, not an IP literal");
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            if (!(properties.getSecurity().isAllowLocalTargets() && port > 0)) {
                throw new BadRequestException("targetUrl port is not allowed");
            }
        }
        requireAllowedDomain(sourceSystem, host);
        validateResolvedAddresses(host);
        return uri;
    }

    private URI parse(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("targetUrl is invalid");
        }
    }

    private void requireAllowedDomain(String sourceSystem, String host) {
        NotificationProperties.Source source = properties.getSecurity().getSources().get(sourceSystem);
        List<String> allowedDomains = source == null ? List.of() : source.getAllowedDomains();
        if (allowedDomains == null || allowedDomains.isEmpty()) {
            throw new BadRequestException("source system has no target domain allowlist");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean allowed = allowedDomains.stream()
                .map(domain -> domain.toLowerCase(Locale.ROOT))
                .anyMatch(domain -> normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain));
        if (!allowed) {
            throw new BadRequestException("targetUrl host is not allowed for source system");
        }
    }

    private void validateResolvedAddresses(String host) {
        if (properties.getSecurity().isAllowLocalTargets()) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isPrivateOrReserved(address)) {
                    throw new BadRequestException("targetUrl resolves to a private or reserved address");
                }
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("targetUrl host cannot be resolved");
        }
    }

    private boolean isIpLiteral(String host) {
        return IPV4_LITERAL.matcher(host).matches() || host.contains(":");
    }

    private boolean isPrivateOrReserved(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address) {
            byte[] bytes = address.getAddress();
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0
                    || first == 10
                    || first == 127
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 100 && second >= 64 && second <= 127)
                    || first >= 224;
        }
        if (address instanceof Inet6Address) {
            byte first = address.getAddress()[0];
            return (first & 0xfe) == 0xfc;
        }
        return true;
    }
}
