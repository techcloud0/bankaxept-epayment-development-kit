package no.bankaxept.epayment.sdk.baseclient;

import no.bankaxept.epayment.sdk.baseclient.spi.HttpClientProvider;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BaseClient {

    private AccessTokenSupplier tokenSupplier;
    private HttpClient httpClient;

    public BaseClient(String baseurl, String apimKey, String username, String password) {
        this(baseurl, apimKey, username, password, Clock.systemDefaultZone(), Executors.newScheduledThreadPool(1));
    }

    public BaseClient(String baseurl, String apimKey, String username, String password, Clock clock, ScheduledExecutorService scheduler) {
        httpClient = ServiceLoader.load(HttpClientProvider.class)
                .findFirst()
                .map(httpClientProvider -> httpClientProvider.create(baseurl))
                .orElseThrow();
        this.tokenSupplier = new AccessTokenSupplier("/token", apimKey, username, password, clock, scheduler);
    }

    public Flow.Publisher<String> post(
            String uri,
            Flow.Publisher<String> body,
            String correlationId
    ) {
        return post(uri, body, correlationId, Collections.emptyMap());
    }

    public Flow.Publisher<String> post(
            String uri,
            Flow.Publisher<String> body,
            String correlationId,
            Map<String, List<String>> headers
    ) {
        var allHeaders = new HashMap<>(headers);
        allHeaders.put("X-Correlation-Id", Collections.singletonList(correlationId));
        allHeaders.put("Authorization", Collections.singletonList("Bearer " + tokenSupplier.get()));
        return httpClient.post(uri, body, allHeaders);
    }

    private class AccessTokenSupplier implements Flow.Subscriber<String>, Supplier<String> {
        private final ScheduledExecutorService scheduler; //TODO error handling

        private final Pattern tokenPattern = Pattern.compile("\"accessToken\"\\s*:\\s*\"(.*)\"");
        private final Pattern expiryPattern = Pattern.compile("\"expiresOn\"\\s*:\\s*(\\d+)");

        private final String uri;
        private final String apimKey;
        private String username;
        private String password;

        private long expiry;
        private String token;

        private final Clock clock;

        private CountDownLatch startUpLatch = new CountDownLatch(1);

        public AccessTokenSupplier(String uri, String apimKey, String username, String password, Clock clock, ScheduledExecutorService scheduler) {
            this.uri = uri;
            this.apimKey = apimKey;
            this.username = username;
            this.password = password;
            this.clock = clock;
            this.scheduler = scheduler;
            fetchNewToken();
        }

        private void fetchNewToken() {
            httpClient.post(uri, null, createHeaders()).subscribe(this);
        }

        private HashMap<String, List<String>> createHeaders() {
            var headers = new LinkedHashMap<String, List<String>>();
            headers.put("Ocp-Apim-Subscription-Key", Collections.singletonList(apimKey));
            headers.put("Authorization", Collections.singletonList(authenticationHeader()));
            return headers;
        }

        private String authenticationHeader() {
            return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        }


        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String item) {
            Matcher tokenMatcher = tokenPattern.matcher(item);
            Matcher expiryMatcher = expiryPattern.matcher(item);
            if (!tokenMatcher.find() || !expiryMatcher.find())
                throw new IllegalStateException("Could not parse token or expiry");
            this.expiry = Long.parseLong(expiryMatcher.group(1));
            this.token = tokenMatcher.group(1);
            startUpLatch.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
            throw new IllegalStateException("Error when fetching token", throwable);
        }

        @Override
        public void onComplete() {
            scheduler.schedule(this::fetchNewToken, tenMinutesBeforeExpiry(), TimeUnit.MILLISECONDS);
        }

        private long tenMinutesBeforeExpiry() {
            return expiry - clock.instant().plus(10, ChronoUnit.MINUTES).toEpochMilli();
        }

        @Override
        public String get() {
            waitForFirstToken();
            return token;
        }

        private void waitForFirstToken() {
            if(token != null) return; //shortcut
            try {
                startUpLatch.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException("Could not get initial token");
            }
        }
    }

}
