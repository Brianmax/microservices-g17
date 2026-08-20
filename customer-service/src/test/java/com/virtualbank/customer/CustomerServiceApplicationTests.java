package com.virtualbank.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:customer;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver"
    })
class CustomerServiceApplicationTests {
  @Autowired TestRestTemplate http;

  @Test
  void healthEndpointIsAvailable() {
    assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void customerApiRequiresJwt() {
    assertThat(
            http.getForEntity(
                    "/api/v1/customers/00000000-0000-0000-0000-000000000001", String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
