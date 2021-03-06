package no.bankaxept.epayment.client.base;

import no.bankaxept.epayment.client.base.accesstoken.*;
import no.bankaxept.epayment.client.base.http.HttpClient;
import no.bankaxept.epayment.client.base.http.HttpResponse;
import no.bankaxept.epayment.client.base.spi.HttpClientProvider;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class BaseClient {
    private final static Duration tokenTimeout = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final AccessTokenPublisher tokenPublisher;
    private String apimKey;

    private BaseClient(HttpClient httpClient, AccessTokenPublisher tokenPublisher, String apimKey) {
        this.httpClient = httpClient;
        this.tokenPublisher = tokenPublisher;
        this.apimKey = apimKey;
    }

    private Map<String, List<String>> filterHeaders(Map<String, List<String>> headers, String correlationId, boolean hasBody) {
        var filteredHeaders = new LinkedHashMap<>(headers);
        filteredHeaders.put("X-Correlation-Id", List.of(correlationId));
        if (!(tokenPublisher instanceof EmptyAccessTokenPublisher))
            filteredHeaders.put("Authorization", List.of("Bearer " + new AccessTokenSubscriber(tokenPublisher).get(tokenTimeout)));
        if (hasBody && !headers.containsKey("Content-Type"))
            filteredHeaders.put("Content-Type", List.of("application/json"));
        if (apimKey != null)
            filteredHeaders.put("Ocp-Apim-Subscription-Key", List.of(apimKey));
        return filteredHeaders;
    }

    public Flow.Publisher<HttpResponse> post(
            String uri,
            Flow.Publisher<String> body,
            String correlationId
    ) {
        return post(uri, body, correlationId, Map.of());
    }

    public Flow.Publisher<HttpResponse> post(
            String uri,
            Flow.Publisher<String> body,
            String correlationId,
            Map<String, List<String>> headers
    ) {
        return httpClient.post(uri, body, filterHeaders(headers, correlationId, true));
    }

    public Flow.Publisher<HttpResponse> delete(
            String uri,
            String correlationId
    ) {
        return httpClient.delete(uri, filterHeaders(Map.of(), correlationId, false));
    }


    public Flow.Publisher<HttpResponse> put(
            String uri,
            String correlationId
    ) {
        return httpClient.put(uri, filterHeaders(Map.of(), correlationId, false));
    }

    public void shutDown() {
        tokenPublisher.shutDown();
    }

    public static class Builder {

        private HttpClient httpClient;
        private String apimKey;
        private AccessTokenPublisher tokenPublisher;

        private Builder() {
        }

        public Builder(String baseurl) {
            this.httpClient = ServiceLoader.load(HttpClientProvider.class)
                    .findFirst()
                    .map(httpClientProvider -> httpClientProvider.create(baseurl))
                    .orElseThrow();
        }

        public Builder apimKey(String apimKey) {
            this.apimKey = apimKey;
            return this;
        }

        public Builder withStaticToken(String token) {
            this.tokenPublisher = new StaticAccessTokenPublisher(token);
            return this;
        }

        public Builder withoutToken() {
            this.tokenPublisher = new EmptyAccessTokenPublisher();
            return this;
        }

        public Builder withSuppliedToken(Supplier<String> tokenSupplier) {
            this.tokenPublisher = new SuppliedAccessTokenPublisher(tokenSupplier);
            return this;
        }

        public Builder withScheduledToken(String username, String password) {
            this.tokenPublisher = new ScheduledAccessTokenPublisher("/bankaxept-epayment/access-token-api/v1/accesstoken", apimKey, username, password, Clock.systemDefaultZone(), Executors.newScheduledThreadPool(1), httpClient);
            return this;
        }

        public Builder withScheduledToken(String username, String password, Clock clock) {
            this.tokenPublisher = new ScheduledAccessTokenPublisher("/bankaxept-epayment/access-token-api/v1/accesstoken", apimKey, username, password, clock, Executors.newScheduledThreadPool(1), httpClient);
            return this;
        }

        public BaseClient build() {
            return new BaseClient(httpClient, tokenPublisher, apimKey);
        }
    }


}
