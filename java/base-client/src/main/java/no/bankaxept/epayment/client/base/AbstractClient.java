package no.bankaxept.epayment.client.base;

import java.util.concurrent.Flow;

public abstract class AbstractClient {

    private final BaseClient baseClient;

    public AbstractClient(BaseClient baseClient) {
        this.baseClient = baseClient;
    }

    public AbstractClient(String baseurl, String apimKey, String username, String password) {
        this.baseClient = new BaseClient(baseurl, apimKey, username, password);
    }

    protected Flow.Publisher<Void> postEmptyResponseBody(String url, SinglePublisher<String> bodyPublisher, String correlationId){
        var responseProcessor = new EmptyResponseProcessor();
        baseClient.post(url, bodyPublisher, correlationId).subscribe(responseProcessor);
        return responseProcessor;
    }
}