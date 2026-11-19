package com.gitbackup.hosts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitbackup.github.GitHubException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authenticated JSON requests with the error handling every host shares. */
public final class HttpJson {
    /** A parsed answer with its headers (some hosts page through headers). */
    public record Page(JsonNode body, HttpHeaders headers) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String hostLabel;
    private final Map<String, String> headers;

    /**
     * @param hostLabel shown in error messages, for example "GitLab"
     * @param headers   sent with every request (authorization, accepted type)
     */
    public HttpJson(String hostLabel, Map<String, String> headers) {
        this.hostLabel = hostLabel;
        this.headers = new LinkedHashMap<>(headers);
    }

    public JsonNode get(String url) throws IOException {
        return page(url).body();
    }

    public Page page(String url) throws IOException {
        HttpResponse<byte[]> response = send(request(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        check(response.statusCode());
        byte[] body = response.body();
        return new Page(body.length == 0 ? JSON.nullNode() : JSON.readTree(body), response.headers());
    }

    /** Sends {@code body} as JSON and parses the answer. */
    public JsonNode post(String url, JsonNode body) throws IOException {
        HttpRequest request = request(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
                .build();
        HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray());
        check(response.statusCode());
        byte[] answer = response.body();
        return answer.length == 0 ? JSON.nullNode() : JSON.readTree(answer);
    }

    /**
     * Downloads a file. Hosts answer file requests with a redirect to a storage server that
     * must not receive the account's token, so the redirect is followed without it.
     */
    public void download(String url, String accept, Path target) throws IOException {
        HttpRequest.Builder first = request(url).header("Accept", accept).GET();
        HttpResponse<InputStream> response = send(first.build(), HttpResponse.BodyHandlers.ofInputStream());
        for (int hops = 0; isRedirect(response.statusCode()) && hops < 5; hops++) {
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException(hostLabel + " sent a redirect without an address."));
            response.body().close();
            HttpRequest next = HttpRequest.newBuilder(URI.create(url).resolve(location))
                    .timeout(Duration.ofMinutes(30)).GET().build();
            response = send(next, HttpResponse.BodyHandlers.ofInputStream());
        }
        try (InputStream in = response.body()) {
            check(response.statusCode());
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private HttpRequest.Builder request(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60));
        headers.forEach(builder::header);
        return builder;
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return HTTP.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while contacting " + hostLabel, e);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private void check(int status) throws GitHubException {
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 401) {
            throw new GitHubException(GitHubException.Kind.UNAUTHORIZED,
                    hostLabel + " rejected the sign-in (401).", status);
        }
        if (status == 403 || status == 429) {
            throw new GitHubException(GitHubException.Kind.FORBIDDEN,
                    hostLabel + " refused the request (" + status + "): rate limit or missing permission.", status);
        }
        if (status == 404 || status == 410) {
            throw new GitHubException(GitHubException.Kind.NOT_FOUND,
                    hostLabel + " has nothing at this address (" + status + ").", status);
        }
        throw new GitHubException(GitHubException.Kind.OTHER, hostLabel + " answered with status " + status + ".", status);
    }

    public static ObjectMapper mapper() {
        return JSON;
    }
}
