package dev.d4nilpzz.auth;

import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.TooManyRequestsResponse;
import io.javalin.http.UnauthorizedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Single entry point for turning a request into an {@link AccessToken} and enforcing
 * permissions on it.
 * <p>
 * Credentials are accepted as HTTP Basic ({@code name:secret}, what Maven and Gradle send),
 * as a Bearer header, or as the session cookie used by the dashboard. Basic is the preferred
 * form because knowing the token name makes the database lookup exact.
 */
public class AuthService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);

    public static final String SESSION_COOKIE = "repossify_session";
    private static final String REALM = "Repossify";

    private final TokenService tokenService;
    private final AuthRateLimiter rateLimiter;

    public AuthService(TokenService tokenService) {
        this(tokenService, AuthRateLimiter.disabled());
    }

    public AuthService(TokenService tokenService, AuthRateLimiter rateLimiter) {
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Resolves the caller, if any. Never throws for anonymous or invalid credentials, so
     * public routes can stay open while still identifying the caller when present.
     */
    public Optional<AccessToken> resolve(Context ctx) {
        AccessToken cached = ctx.attribute("token");
        if (cached != null) return Optional.of(cached);

        Credentials credentials = extract(ctx);
        if (credentials == null) return Optional.empty();

        String client = ctx.ip();
        // A blocked caller is treated as anonymous instead of being rejected here, so public
        // routes keep answering it and verification is never reached.
        if (rateLimiter.isBlocked(client)) return Optional.empty();

        AccessToken token;
        try {
            token = credentials.name() != null
                    ? tokenService.authenticate(credentials.name(), credentials.secret())
                    : tokenService.getTokenBySecret(credentials.secret());
        } catch (Exception e) {
            LOGGER.warn("Credential lookup failed for {}: {}", client, e.getMessage());
            return Optional.empty();
        }

        if (token == null) {
            rateLimiter.recordFailure(client);
            LOGGER.warn("Rejected credentials from {}", client);
            return Optional.empty();
        }

        rateLimiter.recordSuccess(client);
        ctx.attribute("token", token);
        return Optional.of(token);
    }

    /**
     * Requires a token holding at least {@code permission} on {@code path}.
     *
     * @throws UnauthorizedResponse when no valid credentials were supplied
     * @throws ForbiddenResponse    when the caller is known but lacks the permission
     */
    public AccessToken require(Context ctx, String path, RoutePermission permission) {
        AccessToken token = resolve(ctx).orElseThrow(() -> rejectAnonymous(ctx));

        if (!token.hasAccess(path, permission)) {
            throw new ForbiddenResponse(
                    "Token '" + token.name + "' has no " + permission.name().toLowerCase() +
                            " permission for " + path);
        }
        return token;
    }

    /** Requires a token with the MANAGER permission. Used for anything administrative. */
    public AccessToken requireManager(Context ctx) {
        AccessToken token = resolve(ctx).orElseThrow(() -> rejectAnonymous(ctx));

        if (!token.isManager) {
            throw new ForbiddenResponse("This action requires a manager token");
        }
        return token;
    }

    /** True when the caller may act on {@code path}, without throwing. */
    public boolean canAccess(Context ctx, String path, RoutePermission permission) {
        return resolve(ctx).map(token -> token.hasAccess(path, permission)).orElse(false);
    }

    /**
     * Tells Maven and Gradle to retry with credentials. Without this header the client
     * reports a bare 401 instead of using the {@code <server>} entry from settings.xml.
     */
    public void challenge(Context ctx) {
        ctx.header("WWW-Authenticate", "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"");
    }

    /**
     * 429 rather than 401 once the caller is blocked, so a client holding a stale token
     * learns why its retries stopped being answered instead of looping on a bare 401.
     */
    private RuntimeException rejectAnonymous(Context ctx) {
        if (rateLimiter.isBlocked(ctx.ip())) {
            return new TooManyRequestsResponse("Too many failed authentication attempts; retry in "
                    + rateLimiter.window().toSeconds() + "s");
        }
        challenge(ctx);
        return new UnauthorizedResponse("Valid credentials required");
    }

    private Credentials extract(Context ctx) {
        String header = ctx.header("Authorization");

        if (header != null) {
            if (header.startsWith("Bearer ")) {
                String secret = header.substring("Bearer ".length()).trim();
                return secret.isEmpty() ? null : new Credentials(null, secret);
            }
            if (header.startsWith("Basic ")) {
                try {
                    byte[] raw = Base64.getDecoder().decode(header.substring("Basic ".length()).trim());
                    String decoded = new String(raw, StandardCharsets.UTF_8);
                    // Split on the first colon only: the secret itself may contain one.
                    int separator = decoded.indexOf(':');
                    if (separator < 0) return new Credentials(null, decoded);

                    String name = decoded.substring(0, separator);
                    String secret = decoded.substring(separator + 1);
                    if (secret.isEmpty()) return null;
                    return new Credentials(name.isEmpty() ? null : name, secret);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }

        String cookie = ctx.cookie(SESSION_COOKIE);
        return (cookie == null || cookie.isEmpty()) ? null : new Credentials(null, cookie);
    }

    private record Credentials(String name, String secret) {
    }
}
