package com.virtualbank.exchangerate;

import jakarta.validation.constraints.DecimalMin;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/exchange-rates", "/api/v1/internal/exchange-rates"})
class ExchangeRateController {
  private static final Map<String, BigDecimal> USD =
      Map.of(
          "USD",
          BigDecimal.ONE,
          "EUR",
          new BigDecimal("0.92000000"),
          "PEN",
          new BigDecimal("3.75000000"));

  @GetMapping("/quote")
  Quote quote(
      @RequestParam String source,
      @RequestParam String destination,
      @RequestParam @DecimalMin("0.0001") BigDecimal amount) {
    source = source.toUpperCase(Locale.ROOT);
    destination = destination.toUpperCase(Locale.ROOT);
    if (!USD.containsKey(source) || !USD.containsKey(destination))
      throw new IllegalArgumentException("Unsupported currency");
    BigDecimal rate = USD.get(destination).divide(USD.get(source), 8, RoundingMode.HALF_EVEN);
    BigDecimal normalized = amount.setScale(4, RoundingMode.HALF_EVEN);
    return new Quote(
        source,
        destination,
        normalized,
        rate,
        normalized.multiply(rate).setScale(4, RoundingMode.HALF_EVEN),
        LocalDate.now(),
        "MOCK");
  }

  record Quote(
      String sourceCurrency,
      String destinationCurrency,
      BigDecimal sourceAmount,
      BigDecimal effectiveRate,
      BigDecimal destinationAmount,
      LocalDate rateDate,
      String provider) {}
}
