package com.example.meetplatform.common.observability;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Client address and user agent of the current request. The remote address is resolved by Tomcat's
 * RemoteIpValve ({@code server.forward-headers-strategy=native}), which only trusts internal proxies.
 */
public record ClientInfo(String ipAddress, String userAgent) {

    private static final int MAX_USER_AGENT = 512;

    public static ClientInfo current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return from(attrs.getRequest());
        }
        return new ClientInfo(null, null);
    }

    public static ClientInfo from(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua != null && ua.length() > MAX_USER_AGENT) {
            ua = ua.substring(0, MAX_USER_AGENT);
        }
        return new ClientInfo(request.getRemoteAddr(), ua);
    }
}
