import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import online.indigomc.economy.core.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Headless tests of the exact ledger / link / bridge classes shipped in the mod (no Minecraft).
 * Usage: java CoreTest <scratch-dir>            -> runs every test, prints JSON, exit 0 when all pass
 *        java CoreTest --crash-child <journal>  -> transfer loop used by the hard-kill test
 */
public final class CoreTest {
    static final Money M = new Money(2, "credit", "credits");
    static final List<Map<String, Object>> results = new ArrayList<>();
    static Path dir;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--crash-child")) { crashChild(Path.of(args[1])); return; }
        dir = Path.of(args[0]);
        run("basic_transfer_and_history", CoreTest::basic);
        run("insufficient_funds_and_invalid_input", CoreTest::rejects);
        run("idempotency_replay_and_conflict", CoreTest::idempotency);
        run("concurrent_transfers_16_threads", CoreTest::concurrency);
        run("concurrent_duplicate_requests", CoreTest::duplicates);
        run("restart_persistence", CoreTest::restart);
        run("torn_tail_recovery", CoreTest::torn);
        run("tamper_detection_fails_closed", CoreTest::tamper);
        run("hard_kill_during_transfers", CoreTest::hardKill);
        run("name_change_keeps_uuid_balance", CoreTest::rename);
        run("link_code_flow", CoreTest::linking);
        run("shop_holds_settle_refund_recovery", CoreTest::holds);
        run("bridge_http_auth_and_api", CoreTest::bridge);
        run("bridge_and_game_concurrent_same_account", CoreTest::bridgeConcurrency);
        run("discord_games_awards_caps_and_budget", CoreTest::games);
        run("discord_staff_adjust_caps_and_audit", CoreTest::adminAdjust);
        run("casino_bank_rounds_close_restart", CoreTest::casinoLedger);
        run("casino_games_fairness", CoreTest::casinoFairness);
        long failed = results.stream().filter(r -> !(Boolean) r.get("pass")).count();
        JsonObject out = new JsonObject();
        out.add("results", Json.GSON.toJsonTree(results));
        out.addProperty("passed", results.size() - failed);
        out.addProperty("failed", failed);
        System.out.println(Json.GSON.toJson(out));
        System.out.println(failed == 0 ? "CORE_TESTS_PASS" : "CORE_TESTS_FAIL");
        System.exit(failed == 0 ? 0 : 1);
    }

    interface T { Object run() throws Exception; }

    static void run(String name, T t) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        long t0 = System.nanoTime();
        try {
            r.put("detail", t.run());
            r.put("pass", true);
        } catch (Throwable e) {
            r.put("pass", false);
            r.put("detail", e.toString());
            e.printStackTrace();
        }
        r.put("ms", (System.nanoTime() - t0) / 1_000_000);
        results.add(r);
    }

    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }

    static Ledger fresh(String name) throws Exception {
        Path p = dir.resolve(name).resolve("journal.jsonl");
        Files.createDirectories(p.getParent());
        Files.deleteIfExists(p);
        return new Ledger(p, 0);
    }

    static UUID u(String n) { return UUID.nameUUIDFromBytes(("test:" + n).getBytes(StandardCharsets.UTF_8)); }

    static String code(Ledger.LedgerError e) { return e.code; }

    static String expectError(T t) throws Exception {
        try { t.run(); } catch (Ledger.LedgerError e) { return e.code; }
        throw new AssertionError("expected a LedgerError");
    }

    // ------------------------------------------------------------------ tests

    static String basic() throws Exception {
        try (Ledger l = fresh("basic")) {
            UUID a = u("alice"), b = u("bob");
            l.ensureAccount(a, "Alice", "test");
            l.ensureAccount(b, "Bob", "test");
            l.adjust(a, M.parse("1000"), "lab grant", "admin", "console", null);
            Ledger.Result r = l.transfer(a, b, M.parse("250.50"), "rent", "minecraft", "mc:" + a, null);
            check(l.balance(a) == 74950 && l.balance(b) == 25050, "balances");
            Ledger.Posting pa = r.tx().postingFor(Ledger.acct(a));
            check(pa.before() == 100000 && pa.after() == 74950 && pa.amount() == -25050, "posting fields");
            check(l.history(b, 5).get(0).txid().equals(r.tx().txid()), "history newest first");
            check(l.treasury() == -100000, "treasury is -supply");
            return "alice " + M.format(l.balance(a)) + ", bob " + M.format(l.balance(b)) + "; " + l.verify();
        }
    }

    static String rejects() throws Exception {
        try (Ledger l = fresh("rejects")) {
            UUID a = u("a"), b = u("b"), ghost = u("ghost");
            l.ensureAccount(a, "A", "test");
            l.ensureAccount(b, "B", "test");
            l.adjust(a, 500, "grant", "admin", "console", null);
            long seq = l.seq();
            List<String> codes = new ArrayList<>();
            codes.add(expectError(() -> { l.transfer(a, b, 501, "x", "minecraft", "a", null); return null; }));
            codes.add(expectError(() -> { l.transfer(a, b, 0, "x", "minecraft", "a", null); return null; }));
            codes.add(expectError(() -> { l.transfer(a, b, -5, "x", "minecraft", "a", null); return null; }));
            codes.add(expectError(() -> { l.transfer(a, b, Money.MAX + 1, "x", "minecraft", "a", null); return null; }));
            codes.add(expectError(() -> { l.transfer(a, a, 5, "x", "minecraft", "a", null); return null; }));
            codes.add(expectError(() -> { l.transfer(a, ghost, 5, "x", "minecraft", "a", null); return null; }));
            codes.add(expectError(() -> { l.adjust(a, -501, "take", "admin", "console", null); return null; }));
            codes.add(expectError(() -> { l.adjust(a, 5, " ", "admin", "console", null); return null; }));
            check(codes.equals(List.of("insufficient_funds", "invalid_amount", "invalid_amount", "invalid_amount",
                "same_account", "no_such_account", "insufficient_funds", "reason_required")), "codes " + codes);
            check(l.seq() == seq && l.balance(a) == 500 && l.balance(b) == 0, "nothing committed");
            for (String bad : new String[]{"1.234", "-1", "abc", "0", "1e5", "99999999999999999"})
                try { M.parse(bad); throw new AssertionError("parsed " + bad); } catch (IllegalArgumentException ok) { }
            return "rejected " + codes + "; journal seq unchanged at " + seq;
        }
    }

    static String idempotency() throws Exception {
        try (Ledger l = fresh("idem")) {
            UUID a = u("a"), b = u("b");
            l.ensureAccount(a, "A", "test");
            l.ensureAccount(b, "B", "test");
            l.adjust(a, 1000, "grant", "admin", "console", null);
            Ledger.Result r1 = l.transfer(a, b, 100, "pay", "discord", "d", "key-0001");
            Ledger.Result r2 = l.transfer(a, b, 100, "pay", "discord", "d", "key-0001");
            check(!r1.replayed() && r2.replayed() && r1.tx().txid().equals(r2.tx().txid()), "replay returns original");
            check(l.balance(a) == 900, "moved once");
            String c = expectError(() -> { l.transfer(a, b, 101, "pay", "discord", "d", "key-0001"); return null; });
            check(c.equals("idempotency_conflict"), c);
            Ledger.Result r3 = l.transfer(a, b, 100, "pay", "minecraft", "d", "key-0001");   // keys are per source
            check(!r3.replayed() && l.balance(a) == 800, "source-scoped keys");
            return "replay same txid " + r1.tx().txid().substring(0, 8) + ", conflict rejected, per-source scope ok";
        }
    }

    static String concurrency() throws Exception {
        try (Ledger l = fresh("concurrency")) {
            int n = 10;
            UUID[] us = new UUID[n];
            for (int i = 0; i < n; i++) { us[i] = u("c" + i); l.ensureAccount(us[i], "C" + i, "test"); l.adjust(us[i], 10_000, "seed", "admin", "console", null); }
            long seq0 = l.seq();
            AtomicInteger ok = new AtomicInteger(), insufficient = new AtomicInteger(), other = new AtomicInteger();
            ExecutorService ex = Executors.newFixedThreadPool(16);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                final int seed = t;
                fs.add(ex.submit(() -> {
                    Random r = new Random(seed);
                    for (int i = 0; i < 1500; i++) {
                        int x = r.nextInt(n), y = r.nextInt(n);
                        if (x == y) continue;
                        try {
                            l.transfer(us[x], us[y], 1 + r.nextInt(4000), "load", "test", "t" + seed, null);
                            ok.incrementAndGet();
                        } catch (Ledger.LedgerError e) {
                            if (e.code.equals("insufficient_funds")) insufficient.incrementAndGet(); else other.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
            ex.shutdown();
            long total = 0;
            for (UUID x : us) { long b = l.balance(x); check(b >= 0, "negative"); total += b; }
            check(total == n * 10_000L, "conservation " + total);
            check(other.get() == 0, "unexpected errors " + other.get());
            check(l.seq() - seq0 == ok.get(), "one journal line per committed transfer");
            return ok.get() + " committed, " + insufficient.get() + " insufficient-funds rejections, total conserved "
                + total + "; " + l.verify();
        }
    }

    static String duplicates() throws Exception {
        try (Ledger l = fresh("dupes")) {
            UUID a = u("a"), b = u("b");
            l.ensureAccount(a, "A", "test");
            l.ensureAccount(b, "B", "test");
            l.adjust(a, 1000, "grant", "admin", "console", null);
            int rounds = 50;
            AtomicInteger fresh = new AtomicInteger(), replays = new AtomicInteger();
            ExecutorService ex = Executors.newFixedThreadPool(8);
            for (int k = 0; k < rounds; k++) {
                String key = "dup-key-" + k;
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> fs = new ArrayList<>();
                for (int t = 0; t < 8; t++) fs.add(ex.submit(() -> {
                    go.await();
                    Ledger.Result r = l.transfer(a, b, 1, "dup", "discord", "d", key);
                    (r.replayed() ? replays : fresh).incrementAndGet();
                    return null;
                }));
                go.countDown();
                for (Future<?> f : fs) f.get();
            }
            ex.shutdown();
            check(fresh.get() == rounds && replays.get() == rounds * 7, fresh + "/" + replays);
            check(l.balance(a) == 1000 - rounds && l.balance(b) == rounds, "moved once per key");
            return rounds + " keys x 8 simultaneous submits: " + fresh + " commits, " + replays + " replays";
        }
    }

    static String restart() throws Exception {
        Path p = dir.resolve("restart/journal.jsonl");
        Files.createDirectories(p.getParent());
        Files.deleteIfExists(p);
        Map<String, Long> before;
        long seq;
        String txid;
        UUID a = u("a"), b = u("b");
        try (Ledger l = new Ledger(p, 0)) {
            l.ensureAccount(a, "A", "test");
            l.ensureAccount(b, "B", "test");
            l.adjust(a, 777, "grant", "admin", "console", null);
            txid = l.transfer(a, b, 123, "pay", "discord", "d", "restart-key-1").tx().txid();
            l.link(a, "123456789012345678", "test", "test");
            before = l.balancesSnapshot();
            seq = l.seq();
        }
        try (Ledger l = new Ledger(p, 0)) {
            check(l.balancesSnapshot().equals(before) && l.seq() == seq, "state after reopen");
            Ledger.Result r = l.transfer(a, b, 123, "pay", "discord", "d", "restart-key-1");
            check(r.replayed() && r.tx().txid().equals(txid), "idempotency survives restart");
            check(l.uuidForDiscord("123456789012345678").orElseThrow().equals(a), "link survives restart");
            check(l.account(b).orElseThrow().name().equals("B"), "names survive");
            return "seq " + seq + " balances " + before + " identical after reopen; replay after restart ok";
        }
    }

    static String torn() throws Exception {
        Path p = dir.resolve("torn/journal.jsonl");
        Files.createDirectories(p.getParent());
        Files.deleteIfExists(p);
        UUID a = u("a"), b = u("b");
        Map<String, Long> before;
        try (Ledger l = new Ledger(p, 0)) {
            l.ensureAccount(a, "A", "test"); l.ensureAccount(b, "B", "test");
            l.adjust(a, 1000, "grant", "admin", "console", null);
            before = l.balancesSnapshot();
        }
        String partial = "{\"seq\":5,\"prev\":\"abc\",\"txid\":\"x\",\"type\":\"transfer\",\"postings\":[{\"account\":\"mc:";
        Files.writeString(p, partial, StandardOpenOption.APPEND);
        try (Ledger l = new Ledger(p, 0)) {
            check(l.tornNote() != null, "torn tail noticed");
            check(l.balancesSnapshot().equals(before), "state is last acknowledged");
            l.transfer(a, b, 1, "after", "test", "t", null);   // journal still appendable and chain intact
        }
        try (Ledger l = new Ledger(p, 0)) {
            check(l.balance(b) == 1, "post-recovery commit persisted");
            return "torn tail: " + firstNonNull(tornNote(p)) + "; chain continues";
        }
    }

    static String tornNote(Path p) throws Exception {
        try (var s = Files.list(p.getParent())) { return s.map(x -> x.getFileName().toString()).filter(x -> x.contains(".torn-")).findFirst().orElse(null); }
    }

    static String firstNonNull(String s) { return s == null ? "(none)" : s; }

    static String tamper() throws Exception {
        Path p = dir.resolve("tamper/journal.jsonl");
        Files.createDirectories(p.getParent());
        Files.deleteIfExists(p);
        UUID a = u("a"), b = u("b");
        try (Ledger l = new Ledger(p, 0)) {
            l.ensureAccount(a, "A", "test"); l.ensureAccount(b, "B", "test");
            l.adjust(a, 1000, "grant", "admin", "console", null);
            l.transfer(a, b, 10, "pay", "test", "t", null);
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(p));
        int i = 3;   // the admin grant line
        lines.set(i, lines.get(i).replace("\"amount\":1000", "\"amount\":9000").replace("\"after\":1000", "\"after\":9000"));
        Files.writeString(p, String.join("\n", lines) + "\n");
        try (Ledger l = new Ledger(p, 0)) {
            throw new AssertionError("tampered journal opened");
        } catch (Journal.CorruptJournalException e) {
            return "refused to open: " + e.getMessage();
        }
    }

    static String hardKill() throws Exception {
        Path p = dir.resolve("crash/journal.jsonl");
        Files.createDirectories(p.getParent());
        Files.deleteIfExists(p);
        String java = ProcessHandle.current().info().command().orElse("java");
        String cp = System.getProperty("java.class.path");
        List<String> notes = new ArrayList<>();
        Random r = new Random(7);
        for (int round = 0; round < 5; round++) {
            Process child = new ProcessBuilder(java, "-cp", cp, "CoreTest", "--crash-child", p.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Thread.sleep(700 + r.nextInt(900));
            child.destroyForcibly().waitFor();
            try (Ledger l = new Ledger(p, 0)) {
                String v = l.verify();
                long total = 0;
                for (Map.Entry<String, Long> e : l.balancesSnapshot().entrySet())
                    if (e.getKey().startsWith("mc:")) { check(e.getValue() >= 0, "negative"); total += e.getValue(); }
                check(total == -l.treasury(), "supply");
                notes.add("kill " + round + ": seq=" + l.seq() + (l.tornNote() != null ? " torn-tail-truncated" : "") + " ok");
            }
        }
        return String.join("; ", notes);
    }

    static void crashChild(Path p) throws Exception {
        try (Ledger l = new Ledger(p, 0)) {
            UUID[] us = new UUID[4];
            for (int i = 0; i < 4; i++) { us[i] = u("k" + i); l.ensureAccount(us[i], "K" + i, "test"); }
            if (l.balance(us[0]) + l.balance(us[1]) + l.balance(us[2]) + l.balance(us[3]) == 0)
                for (UUID x : us) l.adjust(x, 1_000_000, "seed", "admin", "console", null);
            Random r = new Random();
            while (true) {
                int x = r.nextInt(4), y = (x + 1 + r.nextInt(3)) % 4;
                try { l.transfer(us[x], us[y], 1 + r.nextInt(5000), "crash-load", "test", "child", null); } catch (Ledger.LedgerError ignored) { }
            }
        }
    }

    static String holds() throws Exception {
        Path p = dir.resolve("holds/journal.jsonl");
        Files.createDirectories(p.getParent());
        Files.deleteIfExists(p);
        UUID buyer = u("buyer"), owner = u("owner");
        List<String> notes = new ArrayList<>();
        try (Ledger l = new Ledger(p, 0)) {
            l.ensureAccount(buyer, "Buyer", "test"); l.ensureAccount(owner, "Owner", "test");
            l.adjust(buyer, 1000, "grant", "admin", "console", null);
            JsonObject meta = Json.obj("trader", "lab-trader-1", "item", "minecraft:bread");
            // all-or-nothing: 1001 > 1000 must not take 1000
            check(expectError(() -> l.hold("h-over", buyer, 1001, "shop", meta)).equals("insufficient_funds") && l.balance(buyer) == 1000, "no partial debit");
            Ledger.Result h1 = l.hold("h1", buyer, 300, "shop purchase", meta);
            Ledger.Result h1again = l.hold("h1", buyer, 300, "shop purchase", meta);
            check(h1again.replayed() && l.balance(buyer) == 700, "replayed hold charges once");
            check(l.accountBalance(Ledger.ESCROW) == 300 && l.openHolds().size() == 1, "escrow holds it");
            l.settle("h1", owner, "shop sale");
            Ledger.Result again = l.settle("h1", owner, "shop sale");
            check(again.replayed() && l.balance(owner) == 300 && l.accountBalance(Ledger.ESCROW) == 0, "settle once");
            check(expectError(() -> l.refund("h1", "late refund")).equals("idempotency_conflict") && l.balance(buyer) == 700, "settled hold cannot also be refunded");
            l.hold("h2", buyer, 200, "shop purchase", meta);
            l.refund("h2", "trade failed after payment");
            check(l.balance(buyer) == 700 && l.accountBalance(Ledger.ESCROW) == 0, "refund returns it");
            l.checkpoint("lab save");
            l.hold("h3", buyer, 100, "shop purchase", meta);
            l.settle("h3", owner, "shop sale");
            l.hold("h4", buyer, 50, "shop purchase", meta);   // left open: simulates a crash mid-trade
            check(l.shopTradesSinceLastCheckpoint().size() == 1, "unsaved trade listed");
            notes.add("no partial debit; replayed hold/settle move money once; refund returns; unsaved trades listed");
        }
        try (Ledger l = new Ledger(p, 0)) {
            check(l.openHolds().size() == 1 && l.accountBalance(Ledger.ESCROW) == 50, "open hold survives restart");
            for (Ledger.Tx t : l.openHolds()) l.refund(t.meta().get("hold").getAsString(), "crash recovery");
            check(l.balance(buyer) == 600 && l.balance(owner) == 400 && l.accountBalance(Ledger.ESCROW) == 0, "recovered");
            check(l.shopTradesSinceLastCheckpoint().size() == 1, "unsaved list rebuilt from journal");
            notes.add("after reopen: open hold refunded to payer, escrow 0; " + l.verify());
        }
        return String.join("; ", notes);
    }

    static String rename() throws Exception {
        try (Ledger l = fresh("rename")) {
            UUID a = u("renamer");
            l.ensureAccount(a, "OldName", "test");
            l.adjust(a, 42, "grant", "admin", "console", null);
            l.ensureAccount(a, "NewName", "test");
            check(l.byName("NewName").orElseThrow().uuid().equals(a) && l.byName("OldName").isEmpty(), "name index follows");
            check(l.balance(a) == 42, "balance kept");
            return "UUID-keyed balance kept across rename; old name no longer resolves";
        }
    }

    static String linking() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        try (Ledger l = fresh("links")) {
            Links links = new Links(l, now::get, 300_000, 5, 10, 600_000);
            UUID a = u("a"), b = u("b");
            l.ensureAccount(a, "A", "test"); l.ensureAccount(b, "B", "test");
            String d1 = "111111111111111111", d2 = "222222222222222222";
            List<String> seen = new ArrayList<>();
            Links.Issued c1 = links.issue(a, "A");
            Links.Issued c2 = links.issue(a, "A");   // replaces c1
            seen.add(expectError(() -> links.redeem(c1.code(), d1, "t")));                       // superseded code
            links.redeem(c2.code().toLowerCase().replace("-", " "), d1, "t");                    // tolerant input
            check(l.uuidForDiscord(d1).orElseThrow().equals(a), "linked");
            seen.add(expectError(() -> links.redeem(c2.code(), d1, "t")));                       // replay
            seen.add(expectError(() -> links.issue(a, "A")));                                    // already linked
            Links.Issued cb = links.issue(b, "B");
            seen.add(expectError(() -> links.redeem(cb.code(), d1, "t")));                       // same Discord, 2nd account
            Links.Issued cb2 = links.issue(b, "B");
            now.addAndGet(300_001);
            seen.add(expectError(() -> links.redeem(cb2.code(), d2, "t")));                      // expired
            l.unlink(a, "mc:" + a, "minecraft", "player unlinked");
            Links.Issued c3 = links.issue(a, "A");
            links.redeem(c3.code(), d2, "t");                                                    // relink to a new Discord
            check(l.uuidForDiscord(d2).orElseThrow().equals(a) && l.uuidForDiscord(d1).isEmpty(), "relinked");
            Links.Issued cb3 = links.issue(b, "B");
            seen.add(expectError(() -> links.redeem(cb3.code(), d2, "t")));                      // d2 already on a
            String brute = "333333333333333333";
            int wrong = 0;
            String last = null;
            for (int i = 0; i < 12; i++) { last = expectError(() -> links.redeem("AAAA-AAAA", brute, "t")); wrong++; }
            seen.add(last);
            for (int i = 0; i < 2; i++) links.issue(b, "B");
            seen.add(expectError(() -> links.issue(b, "B")));                                   // issue rate limit (5 per window)
            Links fresh = new Links(l, now::get, 300_000, 5, 10, 600_000);
            seen.add(expectError(() -> fresh.redeem(cb3.code(), "444444444444444444", "t")));    // restart forgets codes
            check(seen.equals(List.of("invalid_or_expired_code", "invalid_or_expired_code", "account_already_linked",
                "discord_already_linked", "invalid_or_expired_code", "discord_already_linked", "rate_limited",
                "rate_limited", "invalid_or_expired_code")), "sequence " + seen);
            long links_ = 0, unlinks = 0;
            for (String line : Files.readAllLines(l.file())) { if (line.contains("\"type\":\"link\"")) links_++; if (line.contains("\"type\":\"unlink\"")) unlinks++; }
            check(links_ == 2 && unlinks == 1, "journaled link events");
            for (String line : Files.readAllLines(l.file()))
                check(!line.contains(c2.code()) && !line.contains(Links.class.getSimpleName()), "no codes in journal");
            return "outcomes " + seen + "; journal has 2 link + 1 unlink, no codes";
        }
    }

    // ------------------------------------------------------------------ bridge

    static final byte[] SECRET = "lab-only-secret-0123456789abcdef-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    static final HttpClient HTTP = HttpClient.newHttpClient();

    record Resp(int status, JsonObject body) {}

    static Resp call(int port, String path, String json, Map<String, String> override) throws Exception {
        String ts = override.getOrDefault("X-Indigo-Timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        String nonce = override.getOrDefault("X-Indigo-Nonce", UUID.randomUUID().toString().replace("-", ""));
        String canonical = "POST\n" + path + "\n" + ts + "\n" + nonce + "\n"
            + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        Map<String, String> h = new HashMap<>(Map.of("X-Indigo-Timestamp", ts, "X-Indigo-Nonce", nonce,
            "X-Indigo-Signature", HexFormat.of().formatHex(m.doFinal(canonical.getBytes(StandardCharsets.UTF_8)))));
        h.putAll(override);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).POST(HttpRequest.BodyPublishers.ofString(json));
        h.forEach(b::header);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), JsonParser.parseString(r.body()).getAsJsonObject());
    }

    static final Bridge.Events NOOP = new Bridge.Events() {
        public void transfer(Ledger.Tx tx, UUID from, UUID to) { }
        public void linked(UUID uuid, boolean linked) { }
    };

    static String bridge() throws Exception {
        try (Ledger l = fresh("bridge")) {
            Links links = new Links(l, System::currentTimeMillis, 300_000, 5, 10, 600_000);
            try (Bridge br = new Bridge(l, links, M, SECRET, NOOP, System::currentTimeMillis, "127.0.0.1", 0, 1000, Runnable::run)) {
                int port = br.port();
                UUID a = u("a"), b = u("b");
                l.ensureAccount(a, "Alice", "test"); l.ensureAccount(b, "Bob", "test");
                l.adjust(a, M.parse("100"), "grant", "admin", "console", null);
                String da = "100000000000000001", db = "100000000000000002";
                List<String> notes = new ArrayList<>();
                // auth failures
                check(call(port, "/v1/account", "{\"discord_user_id\":\"" + da + "\"}", Map.of("X-Indigo-Signature", "00".repeat(32))).status() == 401, "bad sig");
                Resp stale = call(port, "/v1/account", "{}", Map.of("X-Indigo-Timestamp", String.valueOf(System.currentTimeMillis() / 1000 - 3600)));
                check(stale.status() == 401 && stale.body().get("error").getAsString().equals("stale_request"), "stale");
                HttpResponse<String> naked = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/health"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                check(naked.statusCode() == 401, "unsigned");
                // nonce replay
                String nonce = "fixednonce0123456789";
                check(call(port, "/v1/health", "{}", Map.of("X-Indigo-Nonce", nonce)).status() == 200, "first use");
                Resp again = call(port, "/v1/health", "{}", Map.of("X-Indigo-Nonce", nonce));
                check(again.status() == 401 && again.body().get("error").getAsString().equals("replayed_request"), "nonce replay");
                notes.add("401 for bad signature, stale timestamp, unsigned request, replayed nonce");
                // link through the API
                check(!call(port, "/v1/account", "{\"discord_user_id\":\"" + da + "\"}", Map.of()).body().get("linked").getAsBoolean(), "not linked yet");
                String ca = links.issue(a, "Alice").code(), cb = links.issue(b, "Bob").code();
                Resp lr = call(port, "/v1/link/redeem", "{\"discord_user_id\":\"" + da + "\",\"code\":\"" + ca + "\"}", Map.of());
                check(lr.status() == 200 && lr.body().get("name").getAsString().equals("Alice"), "redeem " + lr.body());
                check(call(port, "/v1/link/redeem", "{\"discord_user_id\":\"" + db + "\",\"code\":\"" + ca + "\"}", Map.of()).body().get("error").getAsString().equals("invalid_or_expired_code"), "code replay via API");
                call(port, "/v1/link/redeem", "{\"discord_user_id\":\"" + db + "\",\"code\":\"" + cb + "\"}", Map.of());
                Resp bal = call(port, "/v1/account", "{\"discord_user_id\":\"" + da + "\"}", Map.of());
                check(bal.body().get("balance_minor").getAsLong() == l.balance(a), "same balance as ledger");
                notes.add("/account -> " + bal.body().get("balance_display").getAsString());
                // pay + idempotency
                String pay = "{\"discord_user_id\":\"" + da + "\",\"to_discord_user_id\":\"" + db + "\",\"amount\":\"12.34\",\"idempotency_key\":\"discord-interaction-0001\"}";
                Resp p1 = call(port, "/v1/pay", pay, Map.of()), p2 = call(port, "/v1/pay", pay, Map.of());
                check(p1.status() == 200 && !p1.body().get("replayed").getAsBoolean() && p2.body().get("replayed").getAsBoolean(), "pay replay");
                check(l.balance(b) == 1234, "moved once");
                Resp p3 = call(port, "/v1/pay", pay.replace("12.34", "12.35"), Map.of());
                check(p3.status() == 409 && p3.body().get("error").getAsString().equals("idempotency_conflict"), "conflict");
                Resp p4 = call(port, "/v1/pay", pay.replace("12.34", "999999").replace("interaction-0001", "interaction-0002"), Map.of());
                check(p4.body().get("error").getAsString().equals("insufficient_funds"), "insufficient");
                Resp p5 = call(port, "/v1/pay", pay.replace("\"idempotency_key\":\"discord-interaction-0001\"", "\"idempotency_key\":\"x\""), Map.of());
                check(p5.status() == 400, "idem required");
                Resp tx = call(port, "/v1/transactions", "{\"discord_user_id\":\"" + db + "\",\"limit\":5}", Map.of());
                check(tx.body().getAsJsonArray("transactions").get(0).getAsJsonObject().get("amount_display").getAsString().equals("+12.34"), "tx rows");
                notes.add("pay 12.34 once (replay returned original txid, conflict 409, insufficient 409)");
                // unlink / relink
                check(call(port, "/v1/link/unlink", "{\"discord_user_id\":\"" + db + "\"}", Map.of()).status() == 200, "unlink");
                check(call(port, "/v1/account", "{\"discord_user_id\":\"" + db + "\"}", Map.of()).body().get("linked").getAsBoolean() == false, "unlinked");
                check(call(port, "/v1/link/unlink", "{\"discord_user_id\":\"" + db + "\"}", Map.of()).status() == 404, "unlink twice");
                // input validation
                check(call(port, "/v1/account", "{\"discord_user_id\":\"12; DROP\"}", Map.of()).status() == 400, "snowflake validation");
                check(call(port, "/v1/account", "not json", Map.of()).status() == 400, "bad json");
                return String.join("; ", notes);
            }
        }
    }

    static String games() throws Exception {
        List<String> notes = new ArrayList<>();
        long playerCap = M.parse("50"), globalCap = M.parse("60"), maxAward = M.parse("20");
        UUID a = u("ga"), b = u("gb"), c = u("gc");
        String d1 = "2026-09-29", d2 = "2026-09-30";
        try (Ledger l = fresh("games")) {
            l.ensureAccount(a, "A", "test"); l.ensureAccount(b, "B", "test"); l.ensureAccount(c, "C", "test");
            long supply0 = -l.treasury();
            // ordinary award, idempotent replay, conflicting reuse of a key
            Ledger.Result r1 = l.gameAward(a, M.parse("15"), "trivia", d1, playerCap, globalCap, maxAward, "trivia 1st", "discord:1", "trivia:d1:q1:rank1:a");
            Ledger.Result r1b = l.gameAward(a, M.parse("15"), "trivia", d1, playerCap, globalCap, maxAward, "trivia 1st", "discord:1", "trivia:d1:q1:rank1:a");
            check(!r1.replayed() && r1b.replayed() && r1.tx().txid().equals(r1b.tx().txid()) && l.balance(a) == M.parse("15"), "award once");
            check(expectError(() -> l.gameAward(a, M.parse("5"), "trivia", d1, playerCap, globalCap, maxAward, "x", "discord:1", "trivia:d1:q1:rank1:a")).equals("idempotency_conflict"), "key reuse");
            check(l.accountBalance(Ledger.GAMES) == globalCap - M.parse("15"), "pool topped up to the cap, then paid out");
            // per-award cap, per-player daily cap (all or nothing), global pool cap
            check(expectError(() -> l.gameAward(a, M.parse("21"), "daily", d1, playerCap, globalCap, maxAward, "x", "discord:1", "daily:d1:a-too-big")).equals("cap_award"), "max award");
            l.gameAward(a, M.parse("20"), "daily", d1, playerCap, globalCap, maxAward, "daily", "discord:1", "daily:d1:a");
            check(expectError(() -> l.gameAward(a, M.parse("20"), "trivia", d1, playerCap, globalCap, maxAward, "x", "discord:1", "trivia:d1:q2:rank1:a")).equals("cap_player"), "player cap all-or-nothing");
            check(l.gamesPlayerToday(a, d1) == M.parse("35") && l.balance(a) == M.parse("35"), "nothing partial");
            l.gameAward(b, M.parse("20"), "daily", d1, playerCap, globalCap, maxAward, "daily", "discord:2", "daily:d1:b");
            check(expectError(() -> l.gameAward(c, M.parse("10"), "daily", d1, playerCap, globalCap, maxAward, "x", "discord:3", "daily:d1:c")).equals("cap_global"), "global pool");
            check(l.gamesPoolRemaining(d1, globalCap) == M.parse("5"), "5 left in the pool");
            notes.add("d1: awards 15+20 (A) + 20 (B); caps award/player/global refuse all-or-nothing; pool 60 -> 5");
            // next UTC day: player totals reset; the pool is reset TO the cap (unused budget does not pile up)
            check(l.gamesPlayerToday(a, d2) == 0 && l.gamesPoolRemaining(d2, globalCap) == globalCap, "new day before top-up");
            l.gameAward(c, M.parse("10"), "unscramble", d2, playerCap, globalCap, maxAward, "unscramble", "discord:3", "unscramble:d2:c");
            check(l.accountBalance(Ledger.GAMES) == globalCap - M.parse("10"), "pool reset to cap, not 5 + 60");
            // money is conserved: everything paid or pooled came out of the treasury
            long players = l.balance(a) + l.balance(b) + l.balance(c);
            check(-l.treasury() == supply0 + players + l.accountBalance(Ledger.GAMES), "conservation");
            check(l.verify().startsWith("seq="), "ledger verifies");
            notes.add("d2: pool reset to 60 (not 65), C paid 10; conservation holds; verify ok");
        }
        // restart: caps and the day's pool are rebuilt from the journal
        try (Ledger l = new Ledger(dir.resolve("games").resolve("journal.jsonl"), 0)) {
            check(l.gamesPlayerToday(c, d2) == M.parse("10") && l.gamesPoolRemaining(d2, globalCap) == globalCap - M.parse("10"), "state after restart");
            Ledger.Result again = l.gameAward(c, M.parse("10"), "unscramble", d2, playerCap, globalCap, maxAward, "unscramble", "discord:3", "unscramble:d2:c");
            check(again.replayed(), "idempotency survives restart");
            notes.add("restart: player total, pool and idempotency rebuilt from the journal");
        }
        // HTTP: disabled by default, then the award/allowance routes with caps reported
        try (Ledger l = fresh("games-http")) {
            Links links = new Links(l, System::currentTimeMillis, 300_000, 5, 10, 600_000);
            try (Bridge br = new Bridge(l, links, M, SECRET, NOOP, System::currentTimeMillis, "127.0.0.1", 0, 1000, Runnable::run)) {
                int port = br.port();
                l.ensureAccount(a, "A", "test");
                String da = "200000000000000001", dz = "200000000000000009";
                l.link(a, da, "test", "test");
                String award = "{\"discord_user_id\":\"" + da + "\",\"amount\":\"15\",\"game\":\"trivia\",\"idempotency_key\":\"trivia:2026-09-29T15:00Z:q2:rank1:" + da + "\",\"reason\":\"Trivia: 1st\"}";
                Resp off = call(port, "/v1/games/award", award, Map.of());
                check(off.status() == 503 && off.body().get("error").getAsString().equals("disabled"), "kill switch default off " + off.body());
                br.games(new Bridge.GamesPolicy(true, playerCap, globalCap, maxAward));
                Resp r1 = call(port, "/v1/games/award", award, Map.of()), r2 = call(port, "/v1/games/award", award, Map.of());
                check(r1.status() == 200 && !r1.body().get("replayed").getAsBoolean() && r2.body().get("replayed").getAsBoolean(), "award + replay " + r1.body());
                check(r1.body().get("remaining_player_minor").getAsLong() == playerCap - M.parse("15") && l.balance(a) == M.parse("15"), "remaining reported");
                Resp big = call(port, "/v1/games/award", award.replace("\"15\"", "\"30\"").replace("rank1", "rank9"), Map.of());
                check(big.status() == 409 && big.body().get("error").getAsString().equals("cap_award") && big.body().get("awarded_minor").getAsLong() == 0, "cap code " + big.body());
                Resp nl = call(port, "/v1/games/award", award.replace(da, dz), Map.of());
                check(nl.status() == 404 && nl.body().get("error").getAsString().equals("not_linked"), "not linked");
                Resp badGame = call(port, "/v1/games/award", award.replace("\"trivia\"", "\"Trivia; DROP\""), Map.of());
                check(badGame.status() == 400, "game name validated");
                Resp al = call(port, "/v1/games/allowance", "{\"discord_user_id\":\"" + da + "\"}", Map.of());
                check(al.body().get("enabled").getAsBoolean() && al.body().get("remaining_player_minor").getAsLong() == playerCap - M.parse("15"), "allowance " + al.body());
                notes.add("HTTP: 503 disabled by default; award 200 + replay; cap_award 409 awarded 0; not_linked 404; allowance reports remaining");
            }
        }
        return String.join("; ", notes);
    }

    static String adminAdjust() throws Exception {
        List<String> notes = new ArrayList<>();
        try (Ledger l = fresh("admin-http")) {
            Links links = new Links(l, System::currentTimeMillis, 300_000, 5, 10, 600_000);
            List<String> told = new ArrayList<>();
            Bridge.Events ev = new Bridge.Events() {
                public void transfer(Ledger.Tx tx, UUID from, UUID to) { }
                public void linked(UUID uuid, boolean linked) { }
                public void adminAdjust(Ledger.Tx tx, UUID who, long delta, String reason) { told.add(delta + ":" + reason); }
            };
            try (Bridge br = new Bridge(l, links, M, SECRET, ev, System::currentTimeMillis, "127.0.0.1", 0, 1000, Runnable::run)) {
                int port = br.port();
                UUID a = u("adm-a");
                l.ensureAccount(a, "Alice_1", "test");
                long supply0 = -l.treasury();
                String staff = "300000000000000001";
                String grant = "{\"staff_discord_user_id\":\"" + staff + "\",\"player\":\"alice_1\",\"direction\":\"grant\",\"amount\":\"250\","
                    + "\"reason\":\"build contest prize\",\"idempotency_key\":\"discord-adjust-0001\"}";
                Resp off = call(port, "/v1/admin/adjust", grant, Map.of());
                check(off.status() == 503 && off.body().get("error").getAsString().equals("disabled") && l.balance(a) == 0, "off by default " + off.body());
                br.admin(new Bridge.AdminPolicy(true, M.parse("1000")));
                Resp g1 = call(port, "/v1/admin/adjust", grant, Map.of()), g2 = call(port, "/v1/admin/adjust", grant, Map.of());
                check(g1.status() == 200 && !g1.body().get("replayed").getAsBoolean() && g2.body().get("replayed").getAsBoolean()
                    && g1.body().get("txid").getAsString().equals(g2.body().get("txid").getAsString()), "grant + replay " + g1.body());
                check(l.balance(a) == M.parse("250") && g1.body().get("name").getAsString().equals("Alice_1")
                    && g1.body().get("balance_minor").getAsLong() == M.parse("250") && told.size() == 1, "granted once, player told once");
                Ledger.Tx tx = l.history(a, 1).get(0);
                check(tx.type().equals("admin_adjust") && tx.actor().equals("discord:" + staff) && tx.source().equals("admin")
                    && tx.reason().equals("build contest prize"), "journal names the staff member " + tx);
                notes.add("503 disabled by default; grant 250 once (replay same txid), journal actor discord:<staff>, player told once");
                String take = grant.replace("\"grant\"", "\"take\"").replace("\"250\"", "\"40\"").replace("0001", "0002");
                Resp t1 = call(port, "/v1/admin/adjust", take, Map.of());
                check(t1.status() == 200 && l.balance(a) == M.parse("210") && told.get(1).startsWith("-"), "take " + t1.body());
                Resp cap = call(port, "/v1/admin/adjust", grant.replace("\"250\"", "\"1000.01\"").replace("0001", "0003"), Map.of());
                check(cap.status() == 409 && cap.body().get("error").getAsString().equals("cap_admin") && l.balance(a) == M.parse("210"), "cap " + cap.body());
                Resp atCap = call(port, "/v1/admin/adjust", grant.replace("\"250\"", "\"1000\"").replace("0001", "0004"), Map.of());
                check(atCap.status() == 200 && l.balance(a) == M.parse("1210"), "exactly the cap is allowed");
                Resp neg = call(port, "/v1/admin/adjust", take.replace("\"40\"", "\"1000\"").replace("0002", "0005"), Map.of());
                check(neg.status() == 200 && l.balance(a) == M.parse("210"), "take down to 210");
                Resp under = call(port, "/v1/admin/adjust", take.replace("\"40\"", "\"211\"").replace("0002", "0006"), Map.of());
                check(under.status() == 409 && under.body().get("error").getAsString().equals("insufficient_funds") && l.balance(a) == M.parse("210"), "no negative " + under.body());
                notes.add("take 40; 1000.01 -> 409 cap_admin; 1000 allowed; take below zero -> 409 insufficient_funds");
                Resp noReason = call(port, "/v1/admin/adjust", grant.replace("build contest prize", "   ").replace("0001", "0007"), Map.of());
                check(noReason.status() == 400 && noReason.body().get("error").getAsString().equals("reason_required"), "reason " + noReason.body());
                Resp longReason = call(port, "/v1/admin/adjust", grant.replace("build contest prize", "x".repeat(97)).replace("0001", "0008"), Map.of());
                check(longReason.status() == 400, "reason length");
                Resp nobody = call(port, "/v1/admin/adjust", grant.replace("alice_1", "Nobody").replace("0001", "0009"), Map.of());
                check(nobody.status() == 404 && nobody.body().get("error").getAsString().equals("no_such_player"), "no account " + nobody.body());
                Resp badName = call(port, "/v1/admin/adjust", grant.replace("alice_1", "a b;c").replace("0001", "0010"), Map.of());
                check(badName.status() == 400, "name validated");
                Resp badDir = call(port, "/v1/admin/adjust", grant.replace("\"grant\"", "\"mint\"").replace("0001", "0011"), Map.of());
                check(badDir.status() == 400, "direction validated");
                Resp badAmt = call(port, "/v1/admin/adjust", grant.replace("\"250\"", "\"-5\"").replace("0001", "0012"), Map.of());
                check(badAmt.status() == 400 && badAmt.body().get("error").getAsString().equals("invalid_amount"), "amount validated " + badAmt.body());
                Resp conflict = call(port, "/v1/admin/adjust", grant.replace("\"250\"", "\"251\""), Map.of());
                check(conflict.status() == 409 && conflict.body().get("error").getAsString().equals("idempotency_conflict"), "key reuse " + conflict.body());
                Resp noKey = call(port, "/v1/admin/adjust", grant.replace("\"idempotency_key\":\"discord-adjust-0001\"", "\"idempotency_key\":\"x\""), Map.of());
                check(noKey.status() == 400, "key required");
                Resp badStaff = call(port, "/v1/admin/adjust", grant.replace(staff, "12; DROP"), Map.of());
                check(badStaff.status() == 400, "staff snowflake validated");
                notes.add("400 for blank/long reason, bad name, direction, amount, key, staff id; 404 no_such_player; 409 idempotency_conflict");
                Resp acct = call(port, "/v1/admin/account", "{\"staff_discord_user_id\":\"" + staff + "\",\"player\":\"ALICE_1\"}", Map.of());
                check(acct.status() == 200 && acct.body().get("balance_minor").getAsLong() == M.parse("210")
                    && acct.body().getAsJsonArray("transactions").size() == 5   // 4 staff adjustments + account_open
                    && acct.body().getAsJsonArray("transactions").get(0).getAsJsonObject().get("label").getAsString().equals("staff adjustment"), "account view " + acct.body());
                check(-l.treasury() == supply0 + l.balance(a) && l.verify().startsWith("seq="), "supply follows the adjustments; ledger verifies");
                notes.add("/v1/admin/account (case-insensitive name) shows the balance and last 5 rows; supply conserved; verify ok");
                br.admin(Bridge.AdminPolicy.OFF);
                check(call(port, "/v1/admin/account", "{\"staff_discord_user_id\":\"" + staff + "\",\"player\":\"Alice_1\"}", Map.of()).status() == 503, "switch off again");
            }
        }
        return String.join("; ", notes);
    }

    static String bridgeConcurrency() throws Exception {
        try (Ledger l = fresh("bridge-conc")) {
            Links links = new Links(l, System::currentTimeMillis, 300_000, 5, 10, 600_000);
            try (Bridge br = new Bridge(l, links, M, SECRET, NOOP, System::currentTimeMillis, "127.0.0.1", 0, 100_000, Runnable::run)) {
                int port = br.port();
                UUID a = u("a"), b = u("b"), c = u("c");
                l.ensureAccount(a, "A", "test"); l.ensureAccount(b, "B", "test"); l.ensureAccount(c, "C", "test");
                l.adjust(a, 10_000, "grant", "admin", "console", null);   // 100.00 credits
                String da = "100000000000000011", db = "100000000000000012";
                l.link(a, da, "t", "test"); l.link(b, db, "t", "test");
                AtomicInteger discordOk = new AtomicInteger(), gameOk = new AtomicInteger(), rejected = new AtomicInteger();
                ExecutorService ex = Executors.newFixedThreadPool(12);
                List<Future<?>> fs = new ArrayList<>();
                for (int i = 0; i < 150; i++) {
                    final int k = i;
                    fs.add(ex.submit(() -> {   // Discord side: 1.00 each via HTTP
                        Resp r = call(port, "/v1/pay", "{\"discord_user_id\":\"" + da + "\",\"to_discord_user_id\":\"" + db
                            + "\",\"amount\":\"1\",\"idempotency_key\":\"conc-" + k + "-xxxx\"}", Map.of());
                        if (r.status() == 200) discordOk.incrementAndGet(); else if (r.body().get("error").getAsString().equals("insufficient_funds")) rejected.incrementAndGet();
                        else throw new AssertionError(r.body().toString());
                        return null;
                    }));
                    fs.add(ex.submit(() -> {   // in-game side: 1.00 each directly
                        try { l.transfer(a, c, 100, "game", "minecraft", "mc", null); gameOk.incrementAndGet(); }
                        catch (Ledger.LedgerError e) { if (e.code.equals("insufficient_funds")) rejected.incrementAndGet(); else throw e; }
                        return null;
                    }));
                }
                for (Future<?> f : fs) f.get();
                ex.shutdown();
                check(discordOk.get() + gameOk.get() == 100, "exactly the balance was spent: " + discordOk + "+" + gameOk);
                check(l.balance(a) == 0 && rejected.get() == 200, "no overdraft");
                check(l.balance(b) == discordOk.get() * 100L && l.balance(c) == gameOk.get() * 100L, "credited");
                return "300 racing debits of 1.00 against 100.00: discord " + discordOk + " + game " + gameOk
                    + " = 100 committed, " + rejected + " rejected, final 0; " + l.verify();
            }
        }
    }

    // ------------------------------------------------------------------ casinos (0.5.0)

    static String casinoLedger() throws Exception {
        Path jp;
        String rid;
        UUID own = u("croupier"), pl = u("punter"), other = u("other");
        long fee = 25000;
        try (Ledger l = fresh("casino")) {
            jp = l.file();
            l.ensureAccount(own, "Croupier", "test");
            l.ensureAccount(pl, "Punter", "test");
            l.ensureAccount(other, "Other", "test");
            l.adjust(own, 100000, "lab grant", "admin", "console", null);
            l.adjust(pl, 10000, "lab grant", "admin", "console", null);
            l.adjust(other, 30000, "lab grant", "admin", "console", null);
            long supply0 = -l.treasury();
            // opening: fee leaves circulation, name rules, one per owner, unique names
            check(expectError(() -> l.casinoOpen(own, "x", fee, "o1")).equals("bad_name"), "short name refused");
            check(expectError(() -> l.casinoOpen(pl, "Lucky Seven", fee, "o2")).equals("insufficient_funds") && l.balance(pl) == 10000, "fee needs funds");
            Ledger.Casino c = l.casinoOpen(own, "Lucky Seven", fee, "o3");
            check(l.casinoOpen(own, "Lucky Seven", fee, "o3").id().equals(c.id()), "open is idempotent");
            check(l.balance(own) == 75000 && -l.treasury() == supply0 - fee, "licence fee burned to the treasury");
            check(expectError(() -> l.casinoOpen(own, "Second", fee, "o4")).equals("casino_exists"), "one casino per owner");
            check(expectError(() -> l.casinoOpen(other, "lucky seven", fee, "o5")).equals("name_taken"), "names are unique");
            // bank: deposit, withdraw, bounds
            l.casinoDeposit(own, 50000, "d1");
            check(l.casinoDeposit(own, 50000, "d1").replayed() && l.casinoBank(c.id()) == 50000 && l.balance(own) == 25000, "deposit once");
            check(expectError(() -> l.casinoWithdraw(own, 50001, "w0")).equals("insufficient_funds"), "withdraw bounded by bank");
            l.casinoWithdraw(own, 10000, "w1");
            check(l.casinoBank(c.id()) == 40000 && l.balance(own) == 35000, "withdraw");
            check(expectError(() -> l.casinoWithdraw(pl, 1, "w2")).equals("no_casino"), "only the owner's bank");
            // rounds: reserve covers the best outcome, owner can't play, house must cover
            check(expectError(() -> l.roundOpen("r-own", c.id(), own, 100, 9900, "slots", null)).equals("own_casino"), "owner can't play");
            check(expectError(() -> l.roundOpen("r-big", c.id(), pl, 1000, 99000, "slots", null)).equals("house_cannot_cover")
                && l.balance(pl) == 10000 && l.casinoBank(c.id()) == 40000, "uncovered bet refused, nothing moved");
            check(expectError(() -> l.roundOpen("r-poor", c.id(), pl, 10001, 0, "slots", null)).equals("insufficient_funds"), "stake needs funds");
            rid = "r-bj";
            l.roundOpen(rid, c.id(), pl, 1000, 2000, "blackjack", null);
            check(l.roundOpen(rid, c.id(), pl, 1000, 2000, "blackjack", null).replayed(), "round open idempotent");
            check(l.balance(pl) == 9000 && l.casinoBank(c.id()) == 38000 && l.accountBalance(Ledger.CASINO_ESCROW) == 3000, "escrow holds stake + reserve");
            l.roundRaise(rid, 1000, 0);   // double
            check(l.accountBalance(Ledger.CASINO_ESCROW) == 4000 && l.balance(pl) == 8000, "raise");
            check(expectError(() -> l.roundSettle(rid, 4001, "win")).equals("invalid_amount"), "can't return more than is in play");
            check(expectError(() -> l.casinoClose(own, "c0")).equals("rounds_in_play"), "can't close with a round in play");
            l.roundSettle(rid, 4000, "win");
            check(l.roundSettle(rid, 4000, "win").replayed(), "settle idempotent");
            check(l.balance(pl) == 12000 && l.casinoBank(c.id()) == 38000 && l.accountBalance(Ledger.CASINO_ESCROW) == 0, "doubled win paid from the reserve");
            l.roundOpen("r-lose", c.id(), pl, 500, 17500, "roulette", Json.obj("bet", "STRAIGHT", "pick", 17));
            l.roundSettle("r-lose", 0, "lost on 3");
            check(l.balance(pl) == 11500 && l.casinoBank(c.id()) == 38500, "loss goes to the house");
            l.roundOpen("r-void", c.id(), pl, 70, 6930, "slots", null);
            l.casinoAck(pl);
            // left open on purpose: restart must rebuild the open round, casinos, totals and acks
        }
        try (Ledger l = new Ledger(jp, 0)) {
            Ledger.Casino c = l.casinoOf(own).orElseThrow();
            check(l.openRounds().size() == 1 && l.openRounds().get(0).get("round").getAsString().equals("r-void"), "open round survives restart");
            check(c.rounds() == 2 && c.staked() == 2500 && c.returned() == 4000, "casino totals rebuilt " + c);
            check(l.casinoAcked(pl) && !l.casinoAcked(other), "acks rebuilt");
            l.roundVoid("r-void", "crash recovery");
            check(l.balance(pl) == 11500 && l.casinoBank(c.id()) == 38500 && l.accountBalance(Ledger.CASINO_ESCROW) == 0, "void returns both sides");
            long before = l.balance(own), bank = l.casinoBank(c.id());
            l.casinoClose(own, "c1");
            check(l.balance(own) == before + bank && l.casinoBank(c.id()) == 0 && l.casinoOf(own).isEmpty(), "close returns the bank");
            check(l.casinoOpen(other, "Lucky Seven", 0, "o6") != null, "name free again after close");
            check(expectError(() -> l.roundOpen("r-closed", c.id(), pl, 100, 0, "slots", null)).equals("casino_closed"), "closed casino refuses");
            String v = l.verify();
            long sum = 0;
            for (long x : l.balancesSnapshot().values()) sum += x;
            check(sum == 0, "sums to zero");
            return v;
        }
    }

    static String casinoFairness() throws Exception {
        // slots: exact payback by enumerating every combination of the three 32-stop reels
        long ways = 0, paid = 0, hits = 0;
        int maxMult = 0;
        for (int a = 0; a < 6; a++) for (int b = 0; b < 6; b++) for (int c = 0; c < 6; c++) {
            long w = (long) CasinoGames.WEIGHTS[a] * CasinoGames.WEIGHTS[b] * CasinoGames.WEIGHTS[c];
            int m = CasinoGames.slotMultiplier(a, b, c);
            ways += w;
            paid += w * m;
            if (m > 0) hits += w;
            maxMult = Math.max(maxMult, m);
        }
        double slotRtp = (double) paid / ways;
        check(ways == 32768, "reels are 32 stops");
        check(Math.abs(slotRtp - 0.95001220703125) < 1e-12, "slots payback " + slotRtp);
        check(maxMult == CasinoGames.SLOT_MAX_MULTIPLIER, "reserve matches the top prize");
        // roulette: every bet returns 36/37 of the stake on average
        for (CasinoGames.Bet bet : CasinoGames.Bet.values()) {
            int pick = bet == CasinoGames.Bet.STRAIGHT ? 17 : bet == CasinoGames.Bet.DOZEN || bet == CasinoGames.Bet.COLUMN ? 2 : 0;
            int wins = 0;
            for (int n = 0; n <= 36; n++) if (CasinoGames.wins(bet, pick, n)) wins++;
            check(wins * bet.returns == 36, bet + " pays " + wins * bet.returns + "/37");
        }
        check(CasinoGames.wins(CasinoGames.Bet.STRAIGHT, 0, 0) && !CasinoGames.wins(CasinoGames.Bet.RED, 0, 0), "zero");
        // blackjack: simulate a simple basic strategy; payback must be a little under 1 and every return covered
        java.util.SplittableRandom rng = new java.util.SplittableRandom(20260929L);
        long hands = 2_000_000, stakeUnits = 0, returnedHalves = 0;
        for (long i = 0; i < hands; i++) {
            CasinoGames.Blackjack h = new CasinoGames.Blackjack(rng);
            int up = Math.min(h.dealer.get(0)[0], 10);
            if (up == 1) up = 11;
            while (!h.finished) {
                int v = CasinoGames.Blackjack.value(h.player);
                if (h.canDouble() && (v == 11 || (v == 10 && up < 10))) h.doubleDown();
                else if (v >= 17 || (v >= 13 && up <= 6) || (v == 12 && up >= 4 && up <= 6)) h.stand();
                else h.hit();
            }
            check(h.returnHalves <= (h.doubled ? 8 : 6), "return within the reserve");
            stakeUnits += h.doubled ? 2 : 1;
            returnedHalves += h.returnHalves;
        }
        double bjRtp = returnedHalves / 2.0 / stakeUnits;
        check(bjRtp > 0.97 && bjRtp < 1.0, "blackjack payback " + bjRtp);
        // blackjack money: a doubled win uses exactly stake + 2x reserve + extra stake
        check(CasinoGames.reserveFor(CasinoGames.Game.BLACKJACK, null, 1000) == 2000, "blackjack reserve");
        check(CasinoGames.reserveFor(CasinoGames.Game.ROULETTE, CasinoGames.Bet.STRAIGHT, 100) == 3500, "roulette reserve");
        return String.format(java.util.Locale.ROOT, "slots=%.5f hit=%.3f roulette=%.5f blackjack=%.4f (%d hands)", slotRtp,
            (double) hits / ways, 36.0 / 37, bjRtp, hands);
    }
}
