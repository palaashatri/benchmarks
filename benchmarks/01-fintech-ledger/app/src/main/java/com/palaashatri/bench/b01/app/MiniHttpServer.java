package com.palaashatri.bench.b01.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class MiniHttpServer {
    private final HikariDataSource dataSource;
    private final PrometheusMeterRegistry registry;
    private final Counter txnCounter;
    private final Counter fraudRejectedCounter;
    private final Timer txnTimer;
    private final AtomicLong txnIds = new AtomicLong(1);

    public MiniHttpServer(String benchmark, String title) {
        // Setup HikariCP with H2 in-memory
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:ledger;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(20);
        cfg.setMinimumIdle(4);
        cfg.setConnectionTimeout(3000);
        cfg.setPoolName("ledger-pool");
        this.dataSource = new HikariDataSource(cfg);

        // Setup Micrometer Prometheus registry
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        // Register business metrics
        this.txnCounter = Counter.builder("ledger_transactions_total")
                .description("Total ledger transactions")
                .register(registry);
        this.fraudRejectedCounter = Counter.builder("ledger_fraud_rejected_total")
                .description("Total fraud-rejected transactions")
                .register(registry);
        this.txnTimer = Timer.builder("ledger_txn_duration_seconds")
                .description("Transaction duration")
                .register(registry);

        initSchema();
        seedAccounts();
    }

    private void initSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS accounts (" +
                    "id VARCHAR(32) PRIMARY KEY, " +
                    "balance_cents BIGINT NOT NULL DEFAULT 0, " +
                    "version INT NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS transactions (" +
                    "id VARCHAR(64) PRIMARY KEY, " +
                    "from_acct VARCHAR(32), " +
                    "to_acct VARCHAR(32), " +
                    "amount_cents BIGINT, " +
                    "ts BIGINT, " +
                    "status VARCHAR(16), " +
                    "fraud_score INT)");
        } catch (SQLException e) {
            throw new RuntimeException("Schema init failed", e);
        }
    }

    private void seedAccounts() {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO accounts(id, balance_cents, version) VALUES(?,?,0) " +
                    "ON DUPLICATE KEY UPDATE balance_cents=balance_cents")) {
                for (int i = 1; i <= 2000; i++) {
                    ps.setString(1, String.valueOf(i));
                    ps.setLong(2, 1_000_000L);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            // Override special accounts
            try (PreparedStatement ps = conn.prepareStatement(
                    "MERGE INTO accounts(id, balance_cents, version) KEY(id) VALUES(?,?,0)")) {
                ps.setString(1, "1001"); ps.setLong(2, 1_500_000L); ps.executeUpdate();
                ps.setString(1, "1002"); ps.setLong(2, 1_250_000L); ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Seed failed", e);
        }
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        server.createContext("/health", this::handleHealth);
        server.createContext("/actuator/health", this::handleHealth);
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/actuator/prometheus", this::handleMetrics);
        server.createContext("/transfers", this::handleTransfers);
        server.createContext("/accounts/", this::handleAccounts);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("{\"event\":\"started\",\"port\":" + port + "}");
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        int active = 0;
        int idle = 0;
        try {
            var pool = dataSource;
            active = pool.getHikariPoolMXBean().getActiveConnections();
            idle = pool.getHikariPoolMXBean().getIdleConnections();
        } catch (Exception ignored) { }
        json(ex, 200, "{\"status\":\"UP\",\"db_pool_active\":" + active + ",\"db_pool_idle\":" + idle + "}");
    }

    private void handleMetrics(HttpExchange ex) throws IOException {
        String body = registry.scrape();
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    private void handleTransfers(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            json(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String from = field(body, "from", "1001");
        String to = field(body, "to", "1002");
        long amount = number(body, "amount_cents", 125L);

        long start = System.nanoTime();
        String transferId = "txn-" + txnIds.getAndIncrement();
        boolean approved = false;
        long fromBalance = 0L;
        int fraudScore = 0;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Lock and read from account
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT balance_cents FROM accounts WHERE id=? FOR UPDATE")) {
                    ps.setString(1, from);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) fromBalance = rs.getLong(1);
                    }
                }

                fraudScore = Math.abs((from + to + String.valueOf(amount)).hashCode()) % 1000;

                if (fraudScore > 900) {
                    conn.rollback();
                    fraudRejectedCounter.increment();
                    txnTimer.record(Duration.ofNanos(System.nanoTime() - start));
                    json(ex, 200, "{\"transfer_id\":\"" + transferId +
                            "\",\"approved\":false,\"fraud_score\":" + fraudScore + "}");
                    return;
                }

                // Deduct from sender
                int deducted;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE accounts SET balance_cents=balance_cents-?, version=version+1 " +
                        "WHERE id=? AND balance_cents>=?")) {
                    ps.setLong(1, amount);
                    ps.setString(2, from);
                    ps.setLong(3, amount);
                    deducted = ps.executeUpdate();
                }

                if (deducted != 1) {
                    conn.rollback();
                    txnTimer.record(Duration.ofNanos(System.nanoTime() - start));
                    json(ex, 200, "{\"transfer_id\":\"" + transferId +
                            "\",\"approved\":false,\"reason\":\"insufficient_funds\",\"fraud_score\":" + fraudScore + "}");
                    return;
                }

                // Credit recipient
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE accounts SET balance_cents=balance_cents+?, version=version+1 WHERE id=?")) {
                    ps.setLong(1, amount);
                    ps.setString(2, to);
                    ps.executeUpdate();
                }

                // Record transaction
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO transactions(id,from_acct,to_acct,amount_cents,ts,status,fraud_score) " +
                        "VALUES(?,?,?,?,?,?,?)")) {
                    ps.setString(1, transferId);
                    ps.setString(2, from);
                    ps.setString(3, to);
                    ps.setLong(4, amount);
                    ps.setLong(5, System.currentTimeMillis());
                    ps.setString(6, "COMPLETED");
                    ps.setInt(7, fraudScore);
                    ps.executeUpdate();
                }

                conn.commit();
                approved = true;

                // Re-read from balance for response
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT balance_cents FROM accounts WHERE id=?")) {
                    conn.setAutoCommit(true);
                    ps.setString(1, from);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) fromBalance = rs.getLong(1);
                    }
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            json(ex, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }

        txnCounter.increment();
        txnTimer.record(Duration.ofNanos(System.nanoTime() - start));
        json(ex, 200, "{\"transfer_id\":\"" + transferId +
                "\",\"approved\":" + approved +
                ",\"from_balance_cents\":" + fromBalance +
                ",\"fraud_score\":" + fraudScore + "}");
    }

    private void handleAccounts(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();

        // GET /accounts/{id}/balance
        if (path.matches("/accounts/[^/]+/balance")) {
            String accountId = path.split("/")[2];
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT balance_cents FROM accounts WHERE id=?")) {
                ps.setString(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long bal = rs.getLong(1);
                        json(ex, 200, "{\"account_id\":\"" + escape(accountId) +
                                "\",\"balance_cents\":" + bal + "}");
                    } else {
                        json(ex, 404, "{\"error\":\"account not found\",\"account_id\":\"" + escape(accountId) + "\"}");
                    }
                }
            } catch (SQLException e) {
                json(ex, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            }
            return;
        }

        // GET /accounts/{id}/transactions
        if (path.matches("/accounts/[^/]+/transactions")) {
            String accountId = path.split("/")[2];
            StringBuilder entries = new StringBuilder("[");
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT id,from_acct,to_acct,amount_cents,ts,status,fraud_score " +
                         "FROM transactions WHERE from_acct=? OR to_acct=? ORDER BY ts DESC LIMIT 10")) {
                ps.setString(1, accountId);
                ps.setString(2, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) entries.append(",");
                        entries.append("{\"id\":\"").append(escape(rs.getString(1)))
                                .append("\",\"from\":\"").append(escape(rs.getString(2)))
                                .append("\",\"to\":\"").append(escape(rs.getString(3)))
                                .append("\",\"amount_cents\":").append(rs.getLong(4))
                                .append(",\"ts\":").append(rs.getLong(5))
                                .append(",\"status\":\"").append(escape(rs.getString(6)))
                                .append("\",\"fraud_score\":").append(rs.getInt(7))
                                .append("}");
                        first = false;
                    }
                }
            } catch (SQLException e) {
                json(ex, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
                return;
            }
            entries.append("]");
            json(ex, 200, "{\"account_id\":\"" + escape(accountId) +
                    "\",\"transactions\":" + entries + "}");
            return;
        }

        json(ex, 404, "{\"error\":\"not found\",\"path\":\"" + escape(path) + "\"}");
    }

    // --- Static helpers ---

    private static String field(String body, String name, String fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted); if (key < 0) return fallback;
        int colon = body.indexOf(':', key + quoted.length()); if (colon < 0) return fallback;
        int firstQuote = body.indexOf('"', colon + 1); if (firstQuote < 0) return fallback;
        int secondQuote = body.indexOf('"', firstQuote + 1); if (secondQuote < 0) return fallback;
        return body.substring(firstQuote + 1, secondQuote);
    }

    private static long number(String body, String name, long fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted); if (key < 0) return fallback;
        int colon = body.indexOf(':', key + quoted.length()); if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) end++;
        if (end == start) return fallback;
        try { return Long.parseLong(body.substring(start, end)); } catch (NumberFormatException e) { return fallback; }
    }

    private static String escape(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        bytes(ex, status, "application/json", body);
    }

    private static void bytes(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }
}
