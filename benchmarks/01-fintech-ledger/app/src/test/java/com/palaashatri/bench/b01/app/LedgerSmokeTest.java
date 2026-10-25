package com.palaashatri.bench.b01.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.testkit.AppProcess;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Functional gate for the ledger: real identity, money conservation, and rejection of invalid transfers. */
class LedgerSmokeTest {
    private static final String APP = "com.palaashatri.bench.b01.app.BenchmarkApp";
    private static final Pattern BALANCE = Pattern.compile("\"balance_cents\":(-?\\d+)");

    @Test
    void transfersConserveMoneyAndSelfTransfersAreRejected() {
        try (AppProcess app = AppProcess.builder(APP).start()) {
            String runtime = app.get("/runtime");
            assertTrue(runtime.contains("\"run_token\":\"" + app.token() + "\""), runtime);

            long beforeA = balance(app, 1001);
            long beforeB = balance(app, 1002);
            String transfer = app.request("POST", "/transfers",
                    "{\"from\":\"1001\",\"to\":\"1002\",\"amount_cents\":125}");
            assertTrue(transfer.contains("\"approved\":true"), transfer);
            long afterA = balance(app, 1001);
            long afterB = balance(app, 1002);
            assertEquals(beforeA - 125, afterA);
            assertEquals(beforeB + 125, afterB);
            assertEquals(beforeA + beforeB, afterA + afterB, "money must be conserved");

            AppProcess.Response self = app.exchange("POST", "/transfers",
                    "{\"from\":\"1001\",\"to\":\"1001\",\"amount_cents\":1}");
            assertEquals(400, self.status(), self.body());

            assertTrue(app.get("/metrics").contains("ledger_transactions_total 1"));
        }
    }

    private static long balance(AppProcess app, int account) {
        String body = app.get("/accounts/" + account + "/balance");
        Matcher match = BALANCE.matcher(body);
        assertTrue(match.find(), body);
        return Long.parseLong(match.group(1));
    }
}
