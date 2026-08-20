package com.virtualbank.exchangerate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExchangeRateServiceApplicationTests {
  @Autowired TestRestTemplate http;

  @Test
  void healthEndpointIsAvailable() {
    assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void publicQuoteRequiresJwtButInternalQuoteRemainsAvailableForStaticServiceCalls() {
    assertThat(
            http.getForEntity(
                    "/api/v1/exchange-rates/quote?source=USD&destination=EUR&amount=10",
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    var quote =
        http.getForEntity(
            "/api/v1/internal/exchange-rates/quote?source=USD&destination=EUR&amount=10",
            String.class);
    assertThat(quote.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(quote.getBody()).contains("\"destinationAmount\":9.2000");
  }
}
