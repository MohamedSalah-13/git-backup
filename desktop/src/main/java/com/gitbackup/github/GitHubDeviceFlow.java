package com.gitbackup.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.BooleanSupplier;

/**
 * Sign-in through the browser with GitHub's device flow: the application shows a short code,
 * the user enters it on github.com and approves, and the application receives a token.
 * Needs the Client ID of a GitHub OAuth App with "Enable Device Flow" ticked; a Client ID is
 * public, not a secret.
 */
public final class GitHubDeviceFlow {
    /** Client ID of the "Git Backup" OAuth App. Empty until the app is registered on GitHub. */
    static final String CLIENT_ID = "";
    /**
     * Read access to private repositories needs "repo"; "read:org" lists organisation repositories;
     * "read:user" reads the account's private star lists.
     */
    static final String SCOPES = "repo read:org read:user";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the user has to enter, and where. */
    public record Code(String deviceCode, String userCode, String verificationUri, int intervalSeconds,
                       int expiresInSeconds) {
    }

    /** The user said no, or the code expired; the message says which. */
    public static final class Declined extends IOException {
        Declined(String message) {
            super(message);
        }
    }

    private final String clientId;

    public GitHubDeviceFlow() {
        this(System.getProperty("gitbackup.githubClientId", CLIENT_ID));
    }

    GitHubDeviceFlow(String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    /** False until a Client ID is built in (or given with -Dgitbackup.githubClientId). */
    public boolean available() {
        return !clientId.isEmpty();
    }

    public Code start() throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("scope", SCOPES);
        JsonNode answer = post("https://github.com/login/device/code", form);
        if (answer.hasNonNull("error")) {
            throw new IOException("GitHub refused the sign-in: " + answer.path("error_description").asText(
                    answer.path("error").asText()));
        }
        return new Code(answer.path("device_code").asText(), answer.path("user_code").asText(),
                answer.path("verification_uri").asText("https://github.com/login/device"),
                Math.max(1, answer.path("interval").asInt(5)), answer.path("expires_in").asInt(900));
    }

    /**
     * Asks GitHub every few seconds whether the user approved. Returns the token, or null when
     * {@code cancelled} becomes true.
     */
    public String waitForToken(Code code, BooleanSupplier cancelled) throws IOException {
        long interval = code.intervalSeconds();
        long deadline = System.currentTimeMillis() + code.expiresInSeconds() * 1000L;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("device_code", code.deviceCode());
        form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        while (System.currentTimeMillis() < deadline) {
            for (long waited = 0; waited < interval * 1000; waited += 250) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                sleep(250);
            }
            JsonNode answer = post("https://github.com/login/oauth/access_token", form);
            String token = answer.path("access_token").asText("");
            if (!token.isEmpty()) {
                return token;
            }
            switch (answer.path("error").asText("")) {
                case "authorization_pending" -> {
                    // not approved yet
                }
                case "slow_down" -> interval = Math.max(interval + 5, answer.path("interval").asLong(interval + 5));
                case "access_denied" -> throw new Declined("The sign-in was declined on GitHub.");
                case "expired_token" -> throw new Declined("The code expired. Start the sign-in again.");
                default -> throw new IOException("GitHub refused the sign-in: "
                        + answer.path("error_description").asText(answer.path("error").asText("unknown error")));
            }
        }
        throw new Declined("The code expired. Start the sign-in again.");
    }

    private static JsonNode post(String url, Map<String, String> form) throws IOException {
        StringJoiner body = new StringJoiner("&");
        form.forEach((key, value) -> body.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 500) {
                throw new IOException("GitHub answered with status " + response.statusCode() + ".");
            }
            return JSON.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while contacting GitHub", e);
        }
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
