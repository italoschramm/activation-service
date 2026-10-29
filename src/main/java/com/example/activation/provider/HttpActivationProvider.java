package com.example.activation.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

@Component
class HttpActivationProvider implements ActivationProvider {

    private final RestClient client;

    HttpActivationProvider(
            @Value("${activation.provider.base-url:http://localhost:8081}") String baseUrl,
            @Value("${activation.provider.connect-timeout:PT1S}") Duration connectTimeout,
            @Value("${activation.provider.read-timeout:PT2S}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public Outcome activate(ActivationCommand command) {
        try {
            int status = client.post()
                    .uri("/provider/v1/activate")
                    .header("Idempotency-Key", command.externalId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(command)
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode()
                    .value();
            return status >= 200 && status < 300 ? Outcome.COMPLETED : Outcome.RETRYABLE_FAILURE;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            return status == 429 || status >= 500 ? Outcome.RETRYABLE_FAILURE : Outcome.REJECTED;
        } catch (RestClientException exception) {
            return Outcome.RETRYABLE_FAILURE;
        }
    }
}
