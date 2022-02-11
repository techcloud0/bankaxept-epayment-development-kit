package no.bankaxept.epayment.sdk.webflux;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.matching.EqualToPattern;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import no.bankaxept.epayment.sdk.baseclient.BaseClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.adapter.JdkFlowAdapter;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@WireMockTest(httpPort = 8443)
public class BaseClientTest {

    private final Clock clock = Clock.fixed(Instant.now(), ZoneId.systemDefault());
    private ScheduledExecutorService schedulerSpy;
    private BaseClient baseClient;


    @BeforeEach
    public void setup() {
        schedulerSpy = Mockito.spy(new ScheduledThreadPoolExecutor(1));
    }

    @Test
    public void should_add_all_relevant_headers_and_schedule_refresh() {
        stubTokenEndpoint();
        stubTestEndpoint();
        baseClient = new BaseClient("http://localhost:8443", "key", "username", "password", clock, schedulerSpy);
        StepVerifier.create(JdkFlowAdapter.flowPublisherToFlux(baseClient.post("/test", null, "1")))
                .verifyComplete();
        Mockito.verify(schedulerSpy).schedule(Mockito.any(Runnable.class), Mockito.eq(600000L), Mockito.eq(TimeUnit.MILLISECONDS));
    }

    @Test
    public void should_retry_if_server_error() {
        stubTokenEndpoint(serverError());
        stubTestEndpoint();
        baseClient = new BaseClient("http://localhost:8443", "key", "username", "password", clock, schedulerSpy);
        baseClient.post("/test", null, "1");
        Mockito.verify(schedulerSpy).schedule(Mockito.any(Runnable.class), Mockito.eq(30L), Mockito.eq(TimeUnit.SECONDS));
    }

    @Test
    public void should_not_retry_if_client_error() {
        stubTokenEndpoint(forbidden());
        stubTestEndpoint();
        baseClient = new BaseClient("http://localhost:8443", "key", "username", "password", clock, schedulerSpy);
        baseClient.post("/test", null, "1");
        Mockito.verify(schedulerSpy, Mockito.never()).schedule(Mockito.any(Runnable.class), Mockito.anyLong(), Mockito.any());
    }

    private StubMapping stubTestEndpoint() {
        return stubFor(post("/test")
                .withHeader("Authorization", new EqualToPattern("Bearer a-token"))
                .withHeader("X-Correlation-Id", new EqualToPattern("1"))
                .willReturn(ok()));
    }

    private StubMapping stubTokenEndpoint() {
        return stubFor(post("/token")
                .withHeader("Ocp-Apim-Subscription-Key", new EqualToPattern("key"))
                .withBasicAuth("username", "password")
                .willReturn(ok().withBody(
                        "{\n" +
                                "\"expiresOn\": " + clock.instant().plus(20, ChronoUnit.MINUTES).toEpochMilli() + ",\n" +
                                "\"accessToken\": \"a-token\"\n" +
                                "}")));
    }

    private StubMapping stubTokenEndpoint(ResponseDefinitionBuilder responseBuilder) {
        return stubFor(post("/token")
                .withHeader("Ocp-Apim-Subscription-Key", new EqualToPattern("key"))
                .withBasicAuth("username", "password")
                .willReturn(responseBuilder));
    }

}