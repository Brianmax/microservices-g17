package com.virtualbank.banking;

import java.math.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class BankingService {
  private final JdbcClient db;

  BankingService(JdbcClient db) {
    this.db = db;
  }

  @Transactional
  Account open(UUID owner, String type, String currency) {
    type = type.toUpperCase(Locale.ROOT);
    currency = currency.toUpperCase(Locale.ROOT);
    if (!Set.of("CHECKING", "SAVINGS").contains(type)
        || !Set.of("USD", "EUR", "PEN").contains(currency))
      throw bad("Invalid account type or currency");
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    String number =
        String.format("%020d", Math.abs(id.getMostSignificantBits()) % 1_000_000_000_000_000_000L);
    db.sql(
            "INSERT INTO accounts(id,account_number,owner_id,account_type,currency,balance,status,created_at,updated_at) VALUES(?,?,?,?,?,0,'ACTIVE',?,?)")
        .params(id, number, owner, type, currency, Timestamp.from(now), Timestamp.from(now))
        .update();
    return find(id);
  }

  Account find(UUID id) {
    return db.sql(
            "SELECT id,account_number,owner_id,account_type,currency,balance,status,created_at,updated_at FROM accounts WHERE id=?")
        .param(id)
        .query(Account.class)
        .optional()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
  }

  List<Account> owner(UUID id) {
    return db.sql(
            "SELECT id,account_number,owner_id,account_type,currency,balance,status,created_at,updated_at FROM accounts WHERE owner_id=? ORDER BY created_at")
        .param(id)
        .query(Account.class)
        .list();
  }

  boolean owns(UUID account, UUID user) {
    return db.sql("SELECT count(*) FROM accounts WHERE id=? AND owner_id=?")
            .params(account, user)
            .query(Integer.class)
            .single()
        > 0;
  }

  @Transactional
  Account status(UUID id, String target) {
    Account a = locked(id);
    if ("CLOSED".equals(a.status())) throw conflict("Closed account cannot change status");
    if ("CLOSED".equals(target) && a.balance().signum() != 0)
      throw conflict("Account balance must be zero");
    db.sql("UPDATE accounts SET status=?,updated_at=now(),version=version+1 WHERE id=?")
        .params(target, id)
        .update();
    return find(id);
  }

  @Transactional
  Transaction money(UUID id, BigDecimal amount, String description, boolean deposit) {
    amount = scale(amount);
    Account a = locked(id);
    active(a);
    BigDecimal after = deposit ? a.balance().add(amount) : a.balance().subtract(amount);
    if (after.signum() < 0) throw conflict("Insufficient funds");
    db.sql("UPDATE accounts SET balance=?,updated_at=now(),version=version+1 WHERE id=?")
        .params(after, id)
        .update();
    return ledger(
        id,
        deposit ? "DEPOSIT" : "WITHDRAWAL",
        amount,
        after,
        UUID.randomUUID().toString(),
        description);
  }

  @Transactional
  Posting post(
      UUID command,
      UUID source,
      UUID destination,
      BigDecimal debit,
      BigDecimal credit,
      String reference,
      String description) {
    var replay =
        db.sql(
                "SELECT source_transaction_id,destination_transaction_id FROM posted_commands WHERE command_id=?")
            .param(command)
            .query(Command.class)
            .optional();
    if (replay.isPresent())
      return new Posting(
          command,
          transaction(replay.get().sourceTransactionId()),
          transaction(replay.get().destinationTransactionId()),
          true);
    if (source.equals(destination)) throw bad("Accounts must differ");
    List<UUID> ids = new ArrayList<>(List.of(source, destination));
    ids.sort(UUID::compareTo);
    Map<UUID, Account> locked = new HashMap<>();
    for (UUID id : ids) locked.put(id, locked(id));
    replay =
        db.sql(
                "SELECT source_transaction_id,destination_transaction_id FROM posted_commands WHERE command_id=?")
            .param(command)
            .query(Command.class)
            .optional();
    if (replay.isPresent())
      return new Posting(
          command,
          transaction(replay.get().sourceTransactionId()),
          transaction(replay.get().destinationTransactionId()),
          true);
    Account from = locked.get(source), to = locked.get(destination);
    active(from);
    active(to);
    debit = scale(debit);
    credit = scale(credit);
    if (from.balance().compareTo(debit) < 0) throw conflict("Insufficient funds");
    BigDecimal fromAfter = from.balance().subtract(debit), toAfter = to.balance().add(credit);
    db.sql("UPDATE accounts SET balance=?,updated_at=now(),version=version+1 WHERE id=?")
        .params(fromAfter, source)
        .update();
    db.sql("UPDATE accounts SET balance=?,updated_at=now(),version=version+1 WHERE id=?")
        .params(toAfter, destination)
        .update();
    Transaction out = ledger(source, "TRANSFER_OUT", debit, fromAfter, reference, description),
        in = ledger(destination, "TRANSFER_IN", credit, toAfter, reference, description);
    db.sql("INSERT INTO posted_commands VALUES(?,?,?,now())")
        .params(command, out.id(), in.id())
        .update();
    return new Posting(command, out, in, false);
  }

  List<Transaction> transactions(UUID account) {
    find(account);
    return db.sql(
            "SELECT id,account_id,transaction_type,amount,balance_after,reference,description,created_at FROM transactions WHERE account_id=? ORDER BY created_at DESC,id DESC")
        .param(account)
        .query(Transaction.class)
        .list();
  }

  Transaction transaction(UUID id) {
    return db.sql(
            "SELECT id,account_id,transaction_type,amount,balance_after,reference,description,created_at FROM transactions WHERE id=?")
        .param(id)
        .query(Transaction.class)
        .optional()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
  }

  private Account locked(UUID id) {
    return db.sql(
            "SELECT id,account_number,owner_id,account_type,currency,balance,status,created_at,updated_at FROM accounts WHERE id=? FOR UPDATE")
        .param(id)
        .query(Account.class)
        .optional()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
  }

  private Transaction ledger(
      UUID account,
      String type,
      BigDecimal amount,
      BigDecimal after,
      String reference,
      String description) {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    db.sql("INSERT INTO transactions VALUES(?,?,?,?,?,?,?,?)")
        .params(id, account, type, amount, after, reference, description, Timestamp.from(now))
        .update();
    return new Transaction(id, account, type, amount, after, reference, description, now);
  }

  private void active(Account a) {
    if (!"ACTIVE".equals(a.status())) throw conflict("Account is not active");
  }

  private BigDecimal scale(BigDecimal n) {
    if (n == null || n.signum() <= 0) throw bad("Amount must be positive");
    return n.setScale(4, RoundingMode.HALF_EVEN);
  }

  private ResponseStatusException bad(String m) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
  }

  private ResponseStatusException conflict(String m) {
    return new ResponseStatusException(HttpStatus.CONFLICT, m);
  }

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

  record Transaction(
      UUID id,
      UUID accountId,
      String transactionType,
      BigDecimal amount,
      BigDecimal balanceAfter,
      String reference,
      String description,
      Instant createdAt) {}

  record Command(UUID sourceTransactionId, UUID destinationTransactionId) {}

  record Posting(UUID commandId, Transaction debit, Transaction credit, boolean replay) {}
}
