package dev.d4nilpzz.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

public class AccessToken {
    public final int id;
    public final String type;
    public final String name;

    /**
     * BCrypt hash of the secret. Never leaves the process: {@code /api/auth/me} and the token
     * API serialize this object directly, so exposing it would hand the hash to any client.
     */
    @JsonIgnore
    public final String secret;

    public final String createdAt;

    /** ISO-8601 instant after which this token stops authenticating, or null to never expire. */
    public final String expiresAt;

    public final String description;
    public final List<String> permissions; // MANAGER (m)
    public final List<Route> routes;
    public final boolean isManager;

    public AccessToken(int id, String type, String name, String secret, String description,
                       List<String> permissions, List<Route> routes) {
        this(id, type, name, secret, description, permissions, routes, Instant.now().toString());
    }

    public AccessToken(int id, String type, String name, String secret, String description,
                       List<String> permissions, List<Route> routes, String createdAt) {
        this(id, type, name, secret, description, permissions, routes, createdAt, null);
    }

    public AccessToken(int id, String type, String name, String secret, String description,
                       List<String> permissions, List<Route> routes, String createdAt,
                       String expiresAt) {
        this.expiresAt = expiresAt;
        this.id = id;
        this.type = type;
        this.name = name;
        this.secret = secret;
        this.description = description;
        this.createdAt = createdAt;
        this.permissions = permissions;
        this.routes = routes;
        this.isManager = permissions.stream()
                .anyMatch(p -> p.equalsIgnoreCase("M") || p.equalsIgnoreCase("MANAGER"));
    }

    /**
     * Whether this token has passed its expiry. Serialized, so the dashboard can mark a token
     * as expired rather than leaving the operator to compare timestamps.
     */
    public boolean isExpired() {
        return isExpiredAt(Instant.now());
    }

    boolean isExpiredAt(Instant moment) {
        if (expiresAt == null || expiresAt.isBlank()) return false;
        try {
            return Instant.parse(expiresAt).isBefore(moment);
        } catch (DateTimeParseException e) {
            // An expiry that cannot be read is treated as passed: a corrupt value must never
            // be the reason a token keeps working forever.
            return true;
        }
    }

    /**
     * Whether this token may act on {@code path} with at least {@code required}.
     * <p>
     * Managers always pass. Otherwise a route grants access only when it matches on a
     * path-segment boundary, so a route for {@code /repo/releases/com/foo} does not leak
     * access to {@code /repo/releases/com/foobar}.
     */
    public boolean hasAccess(String path, RoutePermission required) {
        if (isManager) return true;
        if (path == null) return false;

        for (Route route : routes) {
            RoutePermission granted = RoutePermission.parse(route.routePermission());
            if (granted == null || !granted.covers(required)) continue;
            if (matches(path, route.path())) return true;
        }
        return false;
    }

    private static boolean matches(String path, String routePath) {
        if (routePath == null || routePath.isEmpty()) return false;

        String prefix = routePath.endsWith("/") ? routePath.substring(0, routePath.length() - 1) : routePath;
        if (prefix.isEmpty()) return true; // route "/" grants everything below it

        if (!path.startsWith(prefix)) return false;
        // Exact match, or the next character starts a new segment.
        return path.length() == prefix.length() || path.charAt(prefix.length()) == '/';
    }

    public record Route(String path, String routePermission) { // READ (r), WRITE (w)
    }
}
