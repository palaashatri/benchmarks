package com.palaashatri.bench.b01.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class MiniHttpServer {
    private final String benchmark;
    private final HikariDataSource dataSource;
    private final AtomicLong transactionIds = new AtomicLong(1);
    private final AtomicLong approvedTransactions = new AtomicLong();
    private final AtomicLong rejectedTransactions = new AtomicLong();
    private final AtomicLong transactionDurationNs = new AtomicLong();

    public MiniHttpServer(String benchmark) {
        this.benchmark = benchmark;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:ledger;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(4);
        config.setConnectionTimeout(3_000);
        config.setPoolName("ledger-pool");
        dataSource = new HikariDataSource(config);
        initializeDatabase();
    }

    private void initializeDatabase() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE accounts ("
                    + "id VARCHAR(32) PRIMARY KEY,"
                    + "balance_cents BIGINT NOT NULL,"
                    + "version BIGINT NOT NULL)");
            statement.execute("CREATE TABLE ledger_entries ("
                    + "id VARCHAR(64) PRIMARY KEY,"
                    + "from_account VARCHAR(32) NOT NULL,"
                    + "to_account VARCHAR(32) NOT NULL,"
                    + "amount_cents BIGINT NOT NULL,"
                    + "created_at_ms BIGINT NOT NULL,"
                    + "fraud_score INT NOT NULL)");
        } catch (SQLException exception) {
            throw new IllegalStateException("database schema initialization failed", exception);
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO accounts(id,balance_cents,version) VALUES(?,?,0)")) {
            connection.setAutoCommit(false);
            for (int account = 1; account <= 2_000; account++) {
                insert.setString(1, Integer.toString(account));
                insert.setLong(2, 1_000_000L);
                insert.addBatch();
            }
            insert.executeBatch();
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("account seeding failed", exception);
        }
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 512);
        server.createContext("/health", this::health);
        server.createContext("/runtime", this::runtime);
        server.createContext("/metrics", this::metrics);
        server.createContext("/transfers", this::transfers);
        server.createContext("/accounts/", this::accounts);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("{\"event\":\"started\",\"benchmark\":\"%s\",\"port\":%d,\"pid\":%d}%n",
                benchmark, port, ProcessHandle.current().pid());
    }

    private void health(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"status\":\"UP\",\"benchmark\":\"" + benchmark
                + "\",\"pid\":" + ProcessHandle.current().pid() + "}");
    }

    private void runtime(HttpExchange exchange) throws IOException {
        String token = System.getenv().getOrDefault("BENCH_RUN_TOKEN", "");
        StringBuilder arguments = new StringBuilder("[");
        List<String> inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (int index = 0; index < inputArguments.size(); index++) {
            if (index > 0) arguments.append(',');
            arguments.append('"').append(escape(inputArguments.get(index))).append('"');
        }
        arguments.append(']');
        json(exchange, 200, "{\"pid\":" + ProcessHandle.current().pid()
                + ",\"run_token\":\"" + escape(token) + "\""
                + ",\"java_version\":\"" + escape(System.getProperty("java.version")) + "\""
                + ",\"java_vendor\":\"" + escape(System.getProperty("java.vendor")) + "\""
                + ",\"vm_name\":\"" + escape(System.getProperty("java.vm.name")) + "\""
                + ",\"input_arguments\":" + arguments + "}");
    }

    private void metrics(HttpExchange exchange) throws IOException {
        long gcCollections = 0;
        long gcCollectionMs = 0;
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collector.getCollectionCount() >= 0) gcCollections += collector.getCollectionCount();
            if (collector.getCollectionTime() >= 0) gcCollectionMs += collector.getCollectionTime();
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        int active = dataSource.getHikariPoolMXBean().getActiveConnections();
        int idle = dataSource.getHikariPoolMXBean().getIdleConnections();
        String body = "# TYPE ledger_transactions_total counter\n"
                + "ledger_transactions_total " + approvedTransactions.get() + "\n"
                + "# TYPE ledger_rejected_transactions_total counter\n"
                + "ledger_rejected_transactions_total " + rejectedTransactions.get() + "\n"
                + "# TYPE ledger_transaction_duration_seconds_sum counter\n"
                + "ledger_transaction_duration_seconds_sum " + format(transactionDurationNs.get() / 1_000_000_000.0) + "\n"
                + "# TYPE ledger_pool_active gauge\nledger_pool_active " + active + "\n"
                + "# TYPE ledger_pool_idle gauge\nledger_pool_idle " + idle + "\n"
                + "# TYPE jvm_memory_used_bytes gauge\njvm_memory_used_bytes{area=\"heap\"} " + heap.getUsed() + "\n"
                + "# TYPE jvm_gc_collections_total counter\njvm_gc_collections_total " + gcCollections + "\n"
                + "# TYPE jvm_gc_collection_seconds_total counter\njvm_gc_collection_seconds_total " + format(gcCollectionMs / 1_000.0) + "\n";
        bytes(exchange, 200, "text/plain; version=0.0.4", body);
    }

    private void transfers(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        long started = System.nanoTime();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String from = stringField(body, "from", "");
        String to = stringField(body, "to", "");
        long amount = longField(body, "amount_cents", -1);
        if (from.isBlank() || to.isBlank() || from.equals(to) || amount <= 0) {
            rejectedTransactions.incrementAndGet();
            json(exchange, 400, "{\"approved\":false,\"reason\":\"invalid_request\"}");
            return;
        }
        int fraudScore = Math.floorMod((from + ':' + to + ':' + amount).hashCode(), 1_000);
        if (fraudScore >= 970) {
            rejectedTransactions.incrementAndGet();
            json(exchange, 200, "{\"approved\":false,\"reason\":\"fraud\",\"fraud_score\":" + fraudScore + "}");
            return;
        }

        String first = Comparator.<String>naturalOrder().compare(from, to) <= 0 ? from : to;
        String second = first.equals(from) ? to : from;
        String transferId = "txn-" + transactionIds.getAndIncrement();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockAccount(connection, first);
                lockAccount(connection, second);
                long fromBalance = balance(connection, from);
                balance(connection, to);
                if (fromBalance < amount) {
                    connection.rollback();
                    rejectedTransactions.incrementAndGet();
                    json(exchange, 200, "{\"approved\":false,\"reason\":\"insufficient_funds\"}");
                    return;
                }
                updateBalance(connection, from, -amount);
                updateBalance(connection, to, amount);
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO ledger_entries(id,from_account,to_account,amount_cents,created_at_ms,fraud_score) VALUES(?,?,?,?,?,?)")) {
                    insert.setString(1, transferId);
                    insert.setString(2, from);
                    insert.setString(3, to);
                    insert.setLong(4, amount);
                    insert.setLong(5, System.currentTimeMillis());
                    insert.setInt(6, fraudScore);
                    insert.executeUpdate();
                }
                connection.commit();
                approvedTransactions.incrementAndGet();
                transactionDurationNs.addAndGet(System.nanoTime() - started);
                json(exchange, 200, "{\"transfer_id\":\"" + transferId
                        + "\",\"approved\":true,\"from_balance_cents\":" + (fromBalance - amount)
                        + ",\"fraud_score\":" + fraudScore + "}");
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            rejectedTransactions.incrementAndGet();
            json(exchange, 500, "{\"error\":\"database_error\",\"message\":\""
                    + escape(exception.getMessage()) + "\"}");
        }
    }

    private void accounts(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String[] pieces = exchange.getRequestURI().getPath().split("/");
        if (pieces.length != 4) {
            json(exchange, 404, "{\"error\":\"not_found\"}");
            return;
        }
        String accountId = pieces[2];
        if ("balance".equals(pieces[3])) {
            try (Connection connection = dataSource.getConnection()) {
                json(exchange, 200, "{\"account_id\":\"" + escape(accountId)
                        + "\",\"balance_cents\":" + balance(connection, accountId) + "}");
            } catch (SQLException exception) {
                json(exchange, 404, "{\"error\":\"account_not_found\"}");
            }
            return;
        }
        if ("transactions".equals(pieces[3])) {
            List<String> entries = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement query = connection.prepareStatement(
                         "SELECT id,from_account,to_account,amount_cents,created_at_ms,fraud_score "
                                 + "FROM ledger_entries WHERE from_account=? OR to_account=? ORDER BY created_at_ms DESC LIMIT 20")) {
                query.setString(1, accountId);
                query.setString(2, accountId);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        entries.add("{\"id\":\"" + escape(result.getString(1))
                                + "\",\"from\":\"" + escape(result.getString(2))
                                + "\",\"to\":\"" + escape(result.getString(3))
                                + "\",\"amount_cents\":" + result.getLong(4)
                                + ",\"created_at_ms\":" + result.getLong(5)
                                + ",\"fraud_score\":" + result.getInt(6) + "}");
                    }
                }
                json(exchange, 200, "{\"account_id\":\"" + escape(accountId)
                        + "\",\"transactions\":[" + String.join(",", entries) + "]}");
            } catch (SQLException exception) {
                json(exchange, 500, "{\"error\":\"database_error\"}");
            }
            return;
        }
        json(exchange, 404, "{\"error\":\"not_found\"}");
    }

    private static void lockAccount(Connection connection, String accountId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT id FROM accounts WHERE id=? FOR UPDATE")) {
            query.setString(1, accountId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("account does not exist: " + accountId);
            }
        }
    }

    private static long balance(Connection connection, String accountId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT balance_cents FROM accounts WHERE id=?")) {
            query.setString(1, accountId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("account does not exist: " + accountId);
                return result.getLong(1);
            }
        }
    }

    private static void updateBalance(Connection connection, String accountId, long delta) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE accounts SET balance_cents=balance_cents+?,version=version+1 WHERE id=?")) {
            update.setLong(1, delta);
            update.setString(2, accountId);
            if (update.executeUpdate() != 1) throw new SQLException("account update failed: " + accountId);
        }
    }

    private static String stringField(String body, String name, String fallback) {
        String key = "\"" + name + "\"";
        int keyIndex = body.indexOf(key);
        if (keyIndex < 0) return fallback;
        int colon = body.indexOf(':', keyIndex + key.length());
        int start = body.indexOf('"', colon + 1);
        int end = start < 0 ? -1 : body.indexOf('"', start + 1);
        return start < 0 || end < 0 ? fallback : body.substring(start + 1, end);
    }

    private static long longField(String body, String name, long fallback) {
        String key = "\"" + name + "\"";
        int keyIndex = body.indexOf(key);
        if (keyIndex < 0) return fallback;
        int colon = body.indexOf(':', keyIndex + key.length());
        if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) end++;
        try {
            return Long.parseLong(body.substring(start, end));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        bytes(exchange, status, "application/json", body);
    }

    private static void bytes(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
