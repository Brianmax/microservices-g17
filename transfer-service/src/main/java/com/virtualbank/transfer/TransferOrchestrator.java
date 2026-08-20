package com.virtualbank.transfer;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Service
class TransferOrchestrator {
  private static final String SELECT =
      "SELECT id,requester_id,source_account_id,destination_account_id,source_amount amount,destination_amount,source_currency,destination_currency,effective_rate exchange_rate,rate_date exchange_rate_date,rate_provider exchange_rate_provider,status,reference,idempotency_key,description,created_at,completed_at,failure_reason FROM transfers";
  private final JdbcClient db;
  private final RestClient banking;
  private final RestClient rates;

  TransferOrchestrator(
      JdbcClient db,
      RestClient.Builder rest,
      @Value("${services.banking.base-url}") String bankingUrl,
      @Value("${services.exchange-rate.base-url}") String rateUrl) {
    this.db = db;
    this.banking = rest.baseUrl(bankingUrl).build();
    this.rates = rest.baseUrl(rateUrl).build();
  }

  Result execute(UUID requester, boolean canTransferAny, TransferController.Request request) {
    if (request.sourceAccountId().equals(request.destinationAccountId()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Accounts must differ");
    BigDecimal amount = request.amount().setScale(4, RoundingMode.HALF_EVEN);
    String fingerprint = fingerprint(request, amount);
    UUID transferId = UUID.randomUUID(), commandId = UUID.randomUUID();
    boolean created = true;
    Instant now = Instant.now();
    try {
      db.sql(
              "INSERT INTO transfers(id,requester_id,idempotency_key,request_fingerprint,command_id,source_account_id,destination_account_id,source_amount,reference,description,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,'PROCESSING',?,?)")
          .params(
              transferId,
              requester,
              request.idempotencyKey(),
              fingerprint,
              commandId,
              request.sourceAccountId(),
              request.destinationAccountId(),
              amount,
              "TRF-" + transferId,
              request.description(),
              Timestamp.from(now),
              Timestamp.from(now))
          .update();
    } catch (DuplicateKeyException duplicate) {
      Stored existing = storedByKey(requester, request.idempotencyKey());
      if (!existing.requestFingerprint().equals(fingerprint))
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Idempotency key was already used for another request");
      if (!"RETRYABLE".equals(existing.status())) return new Result(response(existing), false);
      int claimed =
          db.sql(
                  "UPDATE transfers SET status='PROCESSING',failure_reason=NULL,updated_at=now() WHERE id=? AND status='RETRYABLE'")
              .param(existing.id())
              .update();
      if (claimed == 0) return new Result(find(existing.id()), false);
      transferId = existing.id();
      commandId = existing.commandId();
      created = false;
    }
    try {
      Account source = account(request.sourceAccountId()),
          destination = account(request.destinationAccountId());
      if (!canTransferAny && !source.ownerId().equals(requester))
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "Source account is not owned by the requester");
      Quote quote =
          source.currency().equals(destination.currency())
              ? new Quote(
                  source.currency(),
                  destination.currency(),
                  amount,
                  BigDecimal.ONE.setScale(8),
                  amount,
                  LocalDate.now(),
                  "INTERNAL")
              : quote(source.currency(), destination.currency(), amount);
      db.sql(
              "UPDATE transfers SET source_currency=?,destination_currency=?,destination_amount=?,effective_rate=?,rate_date=?,rate_provider=?,updated_at=now() WHERE id=?")
          .params(
              quote.sourceCurrency(),
              quote.destinationCurrency(),
              quote.destinationAmount(),
              quote.effectiveRate(),
              quote.rateDate(),
              quote.provider(),
              transferId)
          .update();
      Posting posted =
          banking
              .post()
              .uri("/api/v1/internal/postings")
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  new PostingRequest(
                      commandId,
                      request.sourceAccountId(),
                      request.destinationAccountId(),
                      amount,
                      quote.destinationAmount(),
                      "TRF-" + transferId,
                      request.description()))
              .retrieve()
              .body(Posting.class);
      db.sql(
              "UPDATE transfers SET status='COMPLETED',debit_transaction_id=?,credit_transaction_id=?,completed_at=now(),updated_at=now() WHERE id=?")
          .params(posted.debit().id(), posted.credit().id(), transferId)
          .update();
      return new Result(find(transferId), created);
    } catch (ResourceAccessException ambiguous) {
      db.sql("UPDATE transfers SET status='RETRYABLE',failure_reason=?,updated_at=now() WHERE id=?")
          .params("Downstream result is unknown; retry with the same idempotency key", transferId)
          .update();
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Transfer outcome is pending; retry with the same idempotency key",
          ambiguous);
    } catch (RestClientResponseException downstream) {
      fail(transferId, "Downstream rejected transfer: " + downstream.getStatusText());
      throw new ResponseStatusException(
          HttpStatus.valueOf(downstream.getStatusCode().value()),
          "Transfer rejected by downstream service");
    } catch (ResponseStatusException known) {
      fail(transferId, known.getReason());
      throw known;
    } catch (RuntimeException unexpected) {
      fail(transferId, "Transfer orchestration failed");
      throw unexpected;
    }
  }

  TransferController.Response find(UUID id) {
    return response(stored(id));
  }

  List<TransferController.Response> byRequester(UUID requester) {
    return db.sql(SELECT + " WHERE requester_id=? ORDER BY created_at DESC")
        .param(requester)
        .query(TransferController.Response.class)
        .list();
  }

  List<TransferController.Response> all() {
    return db.sql(SELECT + " ORDER BY created_at DESC")
        .query(TransferController.Response.class)
        .list();
  }

  private Account account(UUID id) {
    return banking.get().uri("/api/v1/internal/accounts/{id}", id).retrieve().body(Account.class);
  }

  private Quote quote(String source, String destination, BigDecimal amount) {
    return rates
        .get()
        .uri(
            u ->
                u.path("/api/v1/internal/exchange-rates/quote")
                    .queryParam("source", source)
                    .queryParam("destination", destination)
                    .queryParam("amount", amount)
                    .build())
        .retrieve()
        .body(Quote.class);
  }

  private void fail(UUID id, String reason) {
    db.sql(
            "UPDATE transfers SET status='FAILED',failure_reason=?,completed_at=now(),updated_at=now() WHERE id=?")
        .params(reason == null ? "Transfer failed" : reason, id)
        .update();
  }

  private Stored stored(UUID id) {
    return db.sql("SELECT * FROM transfers WHERE id=?")
        .param(id)
        .query(Stored.class)
        .optional()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
  }

  private Stored storedByKey(UUID requester, String key) {
    return db.sql("SELECT * FROM transfers WHERE requester_id=? AND idempotency_key=?")
        .params(requester, key)
        .query(Stored.class)
        .single();
  }

  private TransferController.Response response(Stored t) {
    return new TransferController.Response(
        t.id(),
        t.requesterId(),
        t.sourceAccountId(),
        t.destinationAccountId(),
        t.sourceAmount(),
        t.destinationAmount(),
        t.sourceCurrency(),
        t.destinationCurrency(),
        t.effectiveRate(),
        t.rateDate(),
        t.rateProvider(),
        t.status(),
        t.reference(),
        t.idempotencyKey(),
        t.description(),
        t.createdAt(),
        t.completedAt(),
        t.failureReason());
  }

  private String fingerprint(TransferController.Request r, BigDecimal amount) {
    try {
      String value =
          r.sourceAccountId()
              + "|"
              + r.destinationAccountId()
              + "|"
              + amount.toPlainString()
              + "|"
              + Objects.toString(r.description(), "");
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  record Result(TransferController.Response response, boolean created) {}

  record Account(
      UUID id,
      String accountNumber,
      UUID ownerId,
      String accountType,
      String currency,
      BigDecimal balance,
      String status,
      Instant createdAt,
      Instant updatedAt) {}

  record Quote(
      String sourceCurrency,
      String destinationCurrency,
      BigDecimal sourceAmount,
      BigDecimal effectiveRate,
      BigDecimal destinationAmount,
      LocalDate rateDate,
      String provider) {}

  record PostingRequest(
      UUID commandId,
      UUID sourceAccountId,
      UUID destinationAccountId,
      BigDecimal sourceAmount,
      BigDecimal destinationAmount,
      String reference,
      String description) {}

  record Transaction(
      UUID id,
      UUID accountId,
      String transactionType,
      BigDecimal amount,
      BigDecimal balanceAfter,
      String reference,
      String description,
      Instant createdAt) {}

  record Posting(UUID commandId, Transaction debit, Transaction credit, boolean replay) {}

  record Stored(
      UUID id,
      UUID requesterId,
      String idempotencyKey,
      String requestFingerprint,
      UUID commandId,
      UUID sourceAccountId,
      UUID destinationAccountId,
      String sourceCurrency,
      String destinationCurrency,
      BigDecimal sourceAmount,
      BigDecimal destinationAmount,
      BigDecimal effectiveRate,
      LocalDate rateDate,
      String rateProvider,
      String reference,
      String description,
      String status,
      UUID debitTransactionId,
      UUID creditTransactionId,
      String failureReason,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt) {}
}
