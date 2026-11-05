package online.indigomc.economy.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The narrow, authenticated API the official Indigo Discord bot uses. It is a client of the ledger,
 * never a second copy of it: every money action goes through the same {@link Ledger} methods and
 * lock as in-game actions.
 *
 * <p>Transport: HTTP on a private address (loopback by default). Every request is POST with a JSON
 * body and three headers; the signature is HMAC-SHA256 with a shared secret kept outside Git over
 * {@code METHOD \n PATH \n TIMESTAMP \n NONCE \n sha256(body)}. Timestamps must be within 60 s and a
 * nonce is accepted once, so a captured request cannot be replayed. Money-moving calls also require
 * an idempotency key, so a retried request after a lost response moves money at most once.
 * There is no console, RCON or file access through this API.
 */
public final class Bridge implements AutoCloseable {
    public interface Events {
        void transfer(Ledger.Tx tx, UUID from, UUID to);
        void linked(UUID uuid, boolean linked);
        default void gameAward(Ledger.Tx tx, UUID to, String game) { }
        /** A company payment committed through the bridge (tell the online parties in game). */
        default void companyPayment(Ledger.Tx tx) { }
        /** A staff adjustment made from Discord (tell the player in game). */
        default void adminAdjust(Ledger.Tx tx, UUID who, long delta, String reason) { }
    }

    /** Staff adjustments from Discord (0.5.3). Off unless the server config enables it; the cap is per action, in minor units. */
    public record AdminPolicy(boolean enabled, long maxPerAction) {
        public static final AdminPolicy OFF = new AdminPolicy(false, 0);
    }

    private static final Pattern MC_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final int MAX_REASON = 96;
    private volatile AdminPolicy admin = AdminPolicy.OFF;

    public void admin(AdminPolicy policy) { this.admin = policy == null ? AdminPolicy.OFF : policy; }

    /** Discord games (0.4.4). Off unless the server config enables it; amounts in minor units. */
    public record GamesPolicy(boolean enabled, long playerDaily, long globalDaily, long maxAward) {
        public static final GamesPolicy OFF = new GamesPolicy(false, 0, 0, 0);
    }

    private static final Pattern GAME = Pattern.compile("^[a-z][a-z0-9_]{1,23}$");
    private volatile GamesPolicy games = GamesPolicy.OFF;

    public void games(GamesPolicy policy) { this.games = policy == null ? GamesPolicy.OFF : policy; }

    /** Indigo companies (milestone 1). Off unless the server config enables it; reads and payments are role-checked here. */
    private volatile boolean companiesEnabled = false;
    public void companies(boolean enabled) { this.companiesEnabled = enabled; }

    private static final Pattern SNOWFLAKE = Pattern.compile("^[0-9]{15,21}$");
    private static final Pattern IDEM = Pattern.compile("^[A-Za-z0-9:_\\-]{8,80}$");
    private static final int MAX_BODY = 4096;
    private static final long SKEW_MS = 60_000, NONCE_MS = 600_000;

    private final Ledger ledger;
    private final Links links;
    private final Money money;
    private final byte[] secret;
    private final Events events;
    private final LongSupplier clock;
    private final HttpServer http;
    private final ExecutorService pool;
    private final Map<String, Long> nonces = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> rate = new HashMap<>();
    private final int perMinute;
    private final CompanyLedger companies;
    private final Executor gameThread;

    /**
     * @param gameThread runs every balance-changing call. In the server this is the Minecraft server thread,
     *                   so Discord payments are ordered with in-game actions (Lightman's Currency checks a
     *                   balance and then withdraws in two steps and must not be raced).
     */
    public Bridge(Ledger ledger, Links links, Money money, byte[] secret, Events events, LongSupplier clock,
                  String bind, int port, int perMinute, Executor gameThread) throws IOException {
        if (secret.length < 32) throw new IllegalArgumentException("bridge secret must be at least 32 bytes");
        this.ledger = ledger;
        this.links = links;
        this.money = money;
        this.secret = secret;
        this.events = events;
        this.clock = clock;
        this.perMinute = perMinute;
        this.gameThread = gameThread;
        this.companies = new CompanyLedger(ledger);
        this.http = HttpServer.create(new InetSocketAddress(bind, port), 16);
        this.pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "indigo-economy-bridge");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(pool);
        http.createContext("/v1/", this::handle);
        http.start();
    }

    public int port() { return http.getAddress().getPort(); }

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        JsonObject out;
        try {
            if (!"POST".equals(ex.getRequestMethod())) throw new ApiError(405, "method_not_allowed", "POST only.");
            byte[] body = readBody(ex.getRequestBody());
            authenticate(ex, body);
            JsonObject in = body.length == 0 ? new JsonObject() : JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            out = route(ex.getRequestURI().getPath(), in);
        } catch (ApiError e) {
            status = e.status;
            out = Json.obj("ok", false, "error", e.code, "message", e.getMessage());
            if (e.extra != null) for (var en : e.extra.entrySet()) out.add(en.getKey(), en.getValue());
        } catch (Ledger.LedgerError e) {
            status = e.code.equals("ledger_unavailable") ? 503 : 409;
            out = Json.obj("ok", false, "error", e.code, "message", e.getMessage());
        } catch (RuntimeException e) {
            status = 400;
            out = Json.obj("ok", false, "error", "bad_request", "message", "Malformed request.");
        }
        byte[] resp = Json.GSON.toJson(out).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, resp.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(resp); }
    }

    private JsonObject route(String path, JsonObject in) throws ApiError, Ledger.LedgerError {
        if (path.startsWith("/v1/company/")) return company(path, in);
        if (path.startsWith("/v1/admin/")) return admin(path, in);
        switch (path) {
            case "/v1/health":
                return Json.obj("ok", true, "available", ledger.available(), "seq", ledger.seq(), "accounts", ledger.accountCount());
            case "/v1/link/redeem": {
                String d = discord(in, "discord_user_id");
                limit(d);
                String code = Json.str(in, "code");
                if (code == null || code.length() > 20) throw new ApiError(400, "bad_request", "Missing code.");
                Ledger.Tx tx = onGameThread(() -> links.redeem(code, d, "discord:" + d));
                UUID u = UUID.fromString(tx.meta().get("uuid").getAsString());
                events.linked(u, true);
                return account(u, Json.obj("ok", true, "linked", true));
            }
            case "/v1/link/unlink": {
                String d = discord(in, "discord_user_id");
                limit(d);
                UUID u = ledger.uuidForDiscord(d).orElseThrow(() -> new ApiError(404, "not_linked", "No Minecraft account is linked to you."));
                onGameThread(() -> ledger.unlink(u, "discord:" + d, "discord", "unlinked from Discord"));
                events.linked(u, false);
                return Json.obj("ok", true, "linked", false);
            }
            case "/v1/account": {
                String d = discord(in, "discord_user_id");
                limit(d);
                Optional<UUID> u = ledger.uuidForDiscord(d);
                return u.isEmpty() ? Json.obj("ok", true, "linked", false) : account(u.get(), Json.obj("ok", true, "linked", true));
            }
            case "/v1/transactions": {
                String d = discord(in, "discord_user_id");
                limit(d);
                UUID u = ledger.uuidForDiscord(d).orElseThrow(() -> new ApiError(404, "not_linked", "Link your Minecraft account first."));
                int n = in.has("limit") ? Math.max(1, Math.min(20, in.get("limit").getAsInt())) : 10;
                JsonArray rows = new JsonArray();
                for (Ledger.Tx tx : ledger.history(u, n)) rows.add(row(tx, u));
                return account(u, Json.obj("ok", true, "linked", true, "transactions", rows));
            }
            case "/v1/pay": {
                String d = discord(in, "discord_user_id");
                String to = discord(in, "to_discord_user_id");
                limit(d);
                String idem = Json.str(in, "idempotency_key");
                if (idem == null || !IDEM.matcher(idem).matches()) throw new ApiError(400, "bad_request", "An idempotency_key is required.");
                UUID from = ledger.uuidForDiscord(d).orElseThrow(() -> new ApiError(404, "not_linked", "Link your Minecraft account first."));
                UUID target = ledger.uuidForDiscord(to).orElseThrow(() -> new ApiError(404, "recipient_not_linked", "That person hasn't linked a Minecraft account."));
                long amount;
                try {
                    amount = money.parse(Json.str(in, "amount"));
                } catch (IllegalArgumentException e) {
                    throw new ApiError(400, "invalid_amount", e.getMessage());
                }
                String reason = Json.str(in, "reason");
                final long amt = amount;
                Ledger.Result r = onGameThread(() -> ledger.transfer(from, target, amt, reason == null ? "Discord payment" : reason, "discord", "discord:" + d, idem));
                if (!r.replayed()) events.transfer(r.tx(), from, target);
                JsonObject o = account(from, Json.obj("ok", true, "replayed", r.replayed(), "txid", r.tx().txid(),
                    "amount_display", money.format(amount), "to_name", ledger.account(target).map(Ledger.Account::name).orElse("?")));
                return o;
            }
            case "/v1/games/allowance": {
                String d = discord(in, "discord_user_id");
                limit(d);
                GamesPolicy g = games;
                String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
                Optional<UUID> u = ledger.uuidForDiscord(d);
                JsonObject o = Json.obj("ok", true, "enabled", g.enabled(), "linked", u.isPresent(), "day", day,
                    "max_award", money.format(g.maxAward()), "max_award_minor", g.maxAward());
                long pool = ledger.gamesPoolRemaining(day, g.globalDaily());
                o.addProperty("remaining_global_minor", pool);
                o.addProperty("remaining_global", money.format(pool));
                o.addProperty("global_remaining_display", money.format(pool));   // the bot's field names (games contract)
                if (u.isPresent()) {
                    long left = Math.max(0, g.playerDaily() - ledger.gamesPlayerToday(u.get(), day));
                    o.addProperty("remaining_player_minor", left);
                    o.addProperty("remaining_player", money.format(left));
                    o.addProperty("player_remaining_display", money.format(left));
                }
                return o;
            }
            case "/v1/games/award": {
                String d = discord(in, "discord_user_id");
                limit(d);
                GamesPolicy g = games;
                if (!g.enabled()) throw new ApiError(503, "disabled", "Games are resting right now.");
                String idem = Json.str(in, "idempotency_key");
                if (idem == null || !IDEM.matcher(idem).matches()) throw new ApiError(400, "bad_request", "An idempotency_key is required.");
                String game = Json.str(in, "game");
                if (game == null || !GAME.matcher(game).matches()) throw new ApiError(400, "bad_request", "Unknown game.");
                UUID u = ledger.uuidForDiscord(d).orElseThrow(() -> new ApiError(404, "not_linked", "Link your Minecraft account first."));
                long amount;
                try {
                    amount = money.parse(in.has("amount") ? in.get("amount").getAsString() : null);
                } catch (IllegalArgumentException e) {
                    throw new ApiError(400, "invalid_amount", e.getMessage());
                }
                String reason = Json.str(in, "reason");
                String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
                final long amt = amount;
                Ledger.Result r;
                try {
                    r = onGameThread(() -> ledger.gameAward(u, amt, game, day, g.playerDaily(), g.globalDaily(), g.maxAward(),
                        reason == null || reason.isBlank() ? "Discord game: " + game : reason, "discord:" + d, idem));
                } catch (Ledger.LedgerError e) {
                    if (!e.code.startsWith("cap_")) throw e;
                    long left = Math.max(0, g.playerDaily() - ledger.gamesPlayerToday(u, day));
                    long pool = ledger.gamesPoolRemaining(day, g.globalDaily());
                    throw new ApiError(409, e.code, e.getMessage(), Json.obj("awarded_minor", 0,
                        "remaining_player_minor", left, "remaining_player", money.format(left),
                        "remaining_global_minor", pool, "remaining_global", money.format(pool)));
                }
                if (!r.replayed()) events.gameAward(r.tx(), u, game);
                long left = Math.max(0, g.playerDaily() - ledger.gamesPlayerToday(u, day));
                long pool = ledger.gamesPoolRemaining(day, g.globalDaily());
                return account(u, Json.obj("ok", true, "replayed", r.replayed(), "txid", r.tx().txid(),
                    "awarded_minor", amount, "awarded", money.format(amount),
                    "remaining_player_minor", left, "remaining_player", money.format(left),
                    "remaining_global_minor", pool, "remaining_global", money.format(pool)));
            }
            default:
                throw new ApiError(404, "not_found", "No such endpoint.");
        }
    }

    private <T> T onGameThread(Callable<T> call) throws ApiError, Ledger.LedgerError {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            gameThread.execute(() -> {
                try { f.complete(call.call()); } catch (Throwable t) { f.completeExceptionally(t); }
            });
            return f.get(5, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            throw new ApiError(503, "ledger_unavailable", "The server is shutting down.");
        } catch (TimeoutException e) {
            // it may still run: a retry with the same idempotency key returns the original result
            throw new ApiError(503, "busy", "The server is busy; try again with the same request.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiError(503, "busy", "Interrupted.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Ledger.LedgerError le) throw le;
            if (e.getCause() instanceof ApiError ae) throw ae;
            throw new ApiError(500, "internal", "Internal error.");
        }
    }

    private JsonObject account(UUID u, JsonObject o) {
        long bal = ledger.balance(u);
        o.addProperty("uuid", u.toString());
        o.addProperty("name", ledger.account(u).map(Ledger.Account::name).orElse("?"));
        o.addProperty("balance_minor", bal);
        o.addProperty("balance_display", money.format(bal));
        return o;
    }

    private JsonObject row(Ledger.Tx tx, UUID u) {
        Ledger.Posting p = tx.postingFor(Ledger.acct(u));
        String counter = null;
        for (Ledger.Posting q : tx.postings()) {
            if (q.account().equals(Ledger.acct(u))) continue;
            counter = ledger.displayName(q.account());
        }
        String label = switch (tx.type()) {
            case "shop_hold" -> "shop purchase";
            case "shop_settle" -> "shop sale";
            case "shop_refund" -> "shop refund";
            case "admin_adjust" -> "staff adjustment";
            case "company_transfer" -> "company payment";
            case "payroll_run" -> "payroll";
            case "invoice_pay" -> "invoice #" + Json.str(tx.meta(), "number");
            case "company_dissolve" -> "company dissolved";
            default -> tx.type().replace('_', ' ');
        };
        JsonObject o = Json.obj("txid", tx.txid(), "ts", tx.ts(), "type", tx.type(), "label", label, "source", tx.source(), "reason", tx.reason());
        if (p != null) {
            o.addProperty("amount_minor", p.amount());
            o.addProperty("amount_display", money.signed(p.amount()));
            o.addProperty("balance_after_display", money.format(p.after()));
        }
        if (counter != null) o.addProperty("counterparty", counter);
        return o;
    }

    // ---------------------------------------------------------------- staff adjustments

    /**
     * Staff grants and takes from Discord, through the same {@link Ledger#adjust} as {@code /indigoeco grant|take}.
     * The bot checks the staff member's Discord role before calling; this side enforces the switch, the per-action cap,
     * a reason, an existing account and idempotency, and journals the staff member as {@code discord:<id>}.
     */
    private JsonObject admin(String path, JsonObject in) throws ApiError, Ledger.LedgerError {
        AdminPolicy a = admin;
        if (!a.enabled()) throw new ApiError(503, "disabled", "Staff adjustments from Discord are off on this server.");
        String d = discord(in, "staff_discord_user_id");
        limit(d);
        String name = Json.str(in, "player");
        if (name == null || !MC_NAME.matcher(name).matches()) throw new ApiError(400, "bad_request", "A Minecraft name is required.");
        Ledger.Account acct = ledger.byName(name).orElseThrow(() -> new ApiError(404, "no_such_player", "No Indigo account called \"" + name + "\". They need to have joined once."));
        switch (path) {
            case "/v1/admin/account": {
                JsonArray rows = new JsonArray();
                for (Ledger.Tx tx : ledger.history(acct.uuid(), 5)) rows.add(row(tx, acct.uuid()));
                return account(acct.uuid(), Json.obj("ok", true, "transactions", rows));
            }
            case "/v1/admin/adjust": {
                String idem = Json.str(in, "idempotency_key");
                if (idem == null || !IDEM.matcher(idem).matches()) throw new ApiError(400, "bad_request", "An idempotency_key is required.");
                String direction = Json.str(in, "direction");
                if (!"grant".equals(direction) && !"take".equals(direction)) throw new ApiError(400, "bad_request", "direction must be grant or take.");
                String reason = Json.str(in, "reason");
                reason = reason == null ? "" : reason.strip();
                if (reason.isEmpty()) throw new ApiError(400, "reason_required", "Staff adjustments need a reason.");
                if (reason.length() > MAX_REASON) throw new ApiError(400, "bad_request", "The reason can be at most " + MAX_REASON + " characters.");
                long amount;
                try {
                    amount = money.parse(Json.str(in, "amount"));
                } catch (IllegalArgumentException e) {
                    throw new ApiError(400, "invalid_amount", e.getMessage());
                }
                if (amount > a.maxPerAction()) throw new ApiError(409, "cap_admin", "One staff adjustment can be at most " + money.format(a.maxPerAction()) + ".");
                final long delta = "grant".equals(direction) ? amount : -amount;
                final String why = reason;
                Ledger.Result r = onGameThread(() -> ledger.adjust(acct.uuid(), delta, why, "admin", "discord:" + d, idem));
                if (!r.replayed()) events.adminAdjust(r.tx(), acct.uuid(), delta, why);
                return account(acct.uuid(), Json.obj("ok", true, "replayed", r.replayed(), "txid", r.tx().txid(),
                    "direction", direction, "amount_minor", amount, "amount_display", money.format(amount)));
            }
            default:
                throw new ApiError(404, "not_found", "No such endpoint.");
        }
    }

    // ---------------------------------------------------------------- companies

    private JsonObject company(String path, JsonObject in) throws ApiError, Ledger.LedgerError {
        if (!companiesEnabled) throw new ApiError(503, "disabled", "Companies are not enabled on this server.");
        String d = discord(in, "discord_user_id");
        limit(d);
        UUID me = ledger.uuidForDiscord(d).orElseThrow(() -> new ApiError(404, "not_linked", "Link your Minecraft account first."));
        CompanyLedger.Actor actor = CompanyLedger.Actor.discord(d, me);
        if (path.equals("/v1/company/pay")) return companyPay(in, d, me, actor);
        // bridge threads read company state under the ledger lock (every write happens on the game thread under it)
        ledger.lock.lock();
        try {
            return companyRead(path, in, me, actor);
        } finally {
            ledger.lock.unlock();
        }
    }

    private JsonObject companyRead(String path, JsonObject in, UUID me, CompanyLedger.Actor actor) throws ApiError, Ledger.LedgerError {
        Companies st = companies.state();
        if (path.equals("/v1/company/list")) {
            JsonArray rows = new JsonArray();
            for (Companies.Company c : st.memberships(me))
                rows.add(Json.obj("id", c.id(), "name", c.name(), "status", c.status().name().toLowerCase(Locale.ROOT),
                    "role", c.members().get(me).role().label()));
            JsonArray inv = new JsonArray();
            for (Companies.Company c : st.invitesFor(me)) inv.add(Json.obj("id", c.id(), "name", c.name(), "role", c.invites().get(me).label()));
            return Json.obj("ok", true, "companies", rows, "invites", inv);
        }
        Companies.Company co = resolveCompany(st, me, Json.str(in, "company"));
        switch (path) {
            case "/v1/company/info": {
                Companies.Member m = companies.check(actor, co.id(), Companies.Perm.VIEW);
                JsonArray owners = new JsonArray();
                for (Companies.Member x : co.members().values()) if (x.role() == Companies.Role.OWNER) owners.add(ledger.displayName(Ledger.acct(x.uuid())));
                return companyFields(co, m, Json.obj("ok", true, "founder", ledger.displayName(Ledger.acct(co.founder())), "created", co.created(),
                    "description", co.description(), "members", co.members().size(), "owners", owners, "shops", st.shopsOf(co.id()).size()));
            }
            case "/v1/company/members": {
                Companies.Member m = companies.check(actor, co.id(), Companies.Perm.VIEW);
                JsonArray rows = new JsonArray();
                for (Companies.Member x : co.members().values())
                    rows.add(Json.obj("name", ledger.displayName(Ledger.acct(x.uuid())), "role", x.role().label(), "since", x.since(), "pay_grant", x.payGrant()));
                return companyFields(co, m, Json.obj("ok", true, "members", rows));
            }
            case "/v1/company/balance": {
                Companies.Member m = companies.check(actor, co.id(), Companies.Perm.VIEW_FINANCE);
                return companyBalance(co, companyFields(co, m, Json.obj("ok", true)));
            }
            case "/v1/company/transactions": {
                Companies.Member m = companies.check(actor, co.id(), Companies.Perm.VIEW_FINANCE);
                int n = in.has("limit") ? Math.max(1, Math.min(20, in.get("limit").getAsInt())) : 10;
                JsonArray rows = new JsonArray();
                for (Ledger.Tx tx : ledger.history(co.id(), 200)) {
                    if (tx.postingFor(co.id()) == null) continue;   // money only; membership events stay in-game
                    rows.add(companyRow(tx, co.id()));
                    if (rows.size() >= n) break;
                }
                return companyBalance(co, companyFields(co, m, Json.obj("ok", true, "transactions", rows)));
            }
            case "/v1/company/invoices": {
                Companies.Member m = companies.check(actor, co.id(), Companies.Perm.VIEW_FINANCE);
                JsonArray rows = new JsonArray();
                for (Companies.Invoice i : st.invoicesFor(co.id())) {
                    boolean issued = i.issuer().equals(co.id());
                    rows.add(Json.obj("number", i.number(), "id", i.id(), "direction", issued ? "issued" : "received",
                        "counterparty", ledger.displayName(issued ? i.to() : i.issuer()), "amount_minor", i.amount(), "amount_display", money.format(i.amount()),
                        "status", i.status().name().toLowerCase(Locale.ROOT), "memo", i.memo(), "created", i.created(), "paid_txid", i.paidTx()));
                    if (rows.size() >= 20) break;
                }
                return companyFields(co, m, Json.obj("ok", true, "invoices", rows));
            }
            default:
                throw new ApiError(404, "not_found", "No such endpoint.");
        }
    }

    /** Company payment: resolved under the lock, then committed on the game thread (never holding the lock while waiting). */
    private JsonObject companyPay(JsonObject in, String d, UUID me, CompanyLedger.Actor actor) throws ApiError, Ledger.LedgerError {
        String idem = Json.str(in, "idempotency_key");
        if (idem == null || !IDEM.matcher(idem).matches()) throw new ApiError(400, "bad_request", "An idempotency_key is required.");
        String toUser = Json.str(in, "to_discord_user_id"), toCompany = Json.str(in, "to_company");
        if ((toUser == null) == (toCompany == null)) throw new ApiError(400, "bad_request", "Give exactly one of to_discord_user_id or to_company.");
        long amount;
        try {
            amount = money.parse(Json.str(in, "amount"));
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "invalid_amount", e.getMessage());
        }
        String target, cid;
        ledger.lock.lock();
        try {
            Companies st = companies.state();
            cid = resolveCompany(st, me, Json.str(in, "company")).id();
            if (toUser != null) {
                String t = discord(in, "to_discord_user_id");
                target = Ledger.acct(ledger.uuidForDiscord(t).orElseThrow(() -> new ApiError(404, "recipient_not_linked", "That person hasn't linked a Minecraft account.")));
            } else {
                target = st.byName(toCompany).map(Companies.Company::id)
                    .orElseThrow(() -> new ApiError(404, "no_such_company", "No company called \"" + toCompany + "\"."));
            }
        } finally {
            ledger.lock.unlock();
        }
        String reason = Json.str(in, "reason");
        final long amt = amount;
        final String to = target;
        Ledger.Result r = onGameThread(() -> companies.pay(actor, cid, to, amt, reason == null || reason.isBlank() ? "Discord company payment" : reason,
            "discord", idem));
        if (!r.replayed()) events.companyPayment(r.tx());
        ledger.lock.lock();
        try {
            Companies.Company co = companies.state().get(cid).orElseThrow();
            return companyBalance(co, companyFields(co, companies.check(actor, cid, Companies.Perm.VIEW), Json.obj("ok", true, "replayed", r.replayed(),
                "txid", r.tx().txid(), "amount_display", money.format(amount), "to_name", ledger.displayName(to))));
        } finally {
            ledger.lock.unlock();
        }
    }

    /** By name or id; with none given, the caller's only company. Unknown and not-a-member look the same to outsiders. */
    private Companies.Company resolveCompany(Companies st, UUID me, String ref) throws ApiError {
        if (ref == null || ref.isBlank()) {
            List<Companies.Company> mine = st.memberships(me);
            if (mine.size() == 1) return mine.get(0);
            throw new ApiError(400, mine.isEmpty() ? "not_member" : "company_required",
                mine.isEmpty() ? "You are not in a company." : "You are in several companies; say which one.");
        }
        Optional<Companies.Company> c = ref.startsWith(Companies.PREFIX) ? st.get(ref) : st.byName(ref);
        if (c.isEmpty() || !c.get().members().containsKey(me)) throw new ApiError(404, "not_member", "You are not a member of a company by that name.");
        return c.get();
    }

    private JsonObject companyFields(Companies.Company co, Companies.Member m, JsonObject o) {
        o.addProperty("company_id", co.id());
        o.addProperty("company_name", co.name());
        o.addProperty("status", co.status().name().toLowerCase(Locale.ROOT));
        o.addProperty("role", m.role().label());
        return o;
    }

    private JsonObject companyBalance(Companies.Company co, JsonObject o) {
        long bal = ledger.accountBalance(co.id());
        o.addProperty("balance_minor", bal);
        o.addProperty("balance_display", money.format(bal));
        return o;
    }

    private JsonObject companyRow(Ledger.Tx tx, String account) {
        Ledger.Posting p = tx.postingFor(account);
        String counter = null;
        for (Ledger.Posting q : tx.postings()) if (!q.account().equals(account)) counter = counter == null ? ledger.displayName(q.account()) : "several";
        if (tx.type().equals("shop_settle")) counter = ledger.displayName("mc:" + Json.str(tx.meta(), "payer"));
        if (tx.type().equals("payroll_run")) counter = tx.postings().size() - 1 + " employee(s)";
        String label = switch (tx.type()) {
            case "shop_settle" -> "shop sale";
            case "company_transfer" -> p.amount() > 0 ? "received" : "paid";
            case "payroll_run" -> "payroll " + Json.str(tx.meta(), "run");
            case "invoice_pay" -> "invoice #" + Json.str(tx.meta(), "number");
            case "company_dissolve" -> "dissolution payout";
            default -> tx.type().replace('_', ' ');
        };
        JsonObject o = Json.obj("txid", tx.txid(), "ts", tx.ts(), "type", tx.type(), "label", label, "source", tx.source(), "reason", tx.reason(),
            "amount_minor", p.amount(), "amount_display", money.signed(p.amount()), "balance_after_display", money.format(p.after()));
        if (counter != null) o.addProperty("counterparty", counter);
        return o;
    }

    private String discord(JsonObject in, String key) throws ApiError {
        String d = Json.str(in, key);
        if (d == null || !SNOWFLAKE.matcher(d).matches()) throw new ApiError(400, "bad_request", "Invalid " + key + ".");
        return d;
    }

    private synchronized void limit(String discordId) throws ApiError {
        long now = clock.getAsLong();
        ArrayDeque<Long> d = rate.computeIfAbsent(discordId, k -> new ArrayDeque<>());
        while (!d.isEmpty() && d.peekFirst() < now - 60_000) d.removeFirst();
        if (d.size() >= perMinute) throw new ApiError(429, "rate_limited", "Slow down a little and try again.");
        d.addLast(now);
    }

    private void authenticate(HttpExchange ex, byte[] body) throws ApiError {
        String ts = ex.getRequestHeaders().getFirst("X-Indigo-Timestamp");
        String nonce = ex.getRequestHeaders().getFirst("X-Indigo-Nonce");
        String sig = ex.getRequestHeaders().getFirst("X-Indigo-Signature");
        if (ts == null || nonce == null || sig == null || nonce.length() < 16 || nonce.length() > 64)
            throw new ApiError(401, "unauthorized", "Unauthorized.");
        long t;
        try { t = Long.parseLong(ts) * 1000; } catch (NumberFormatException e) { throw new ApiError(401, "unauthorized", "Unauthorized."); }
        long now = clock.getAsLong();
        if (Math.abs(now - t) > SKEW_MS) throw new ApiError(401, "stale_request", "Unauthorized.");
        String canonical = ex.getRequestMethod() + "\n" + ex.getRequestURI().getPath() + "\n" + ts + "\n" + nonce + "\n" + sha256(body);
        byte[] want = hmac(canonical);
        byte[] got;
        try { got = HexFormat.of().parseHex(sig); } catch (IllegalArgumentException e) { throw new ApiError(401, "unauthorized", "Unauthorized."); }
        if (!MessageDigest.isEqual(want, got)) throw new ApiError(401, "unauthorized", "Unauthorized.");
        synchronized (nonces) {
            nonces.values().removeIf(exp -> exp < now);
            if (nonces.containsKey(nonce)) throw new ApiError(401, "replayed_request", "Unauthorized.");
            if (nonces.size() > 100_000) throw new ApiError(503, "busy", "Busy.");
            nonces.put(nonce, now + NONCE_MS);
        }
    }

    private byte[] hmac(String s) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret, "HmacSHA256"));
            return m.doFinal(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] readBody(InputStream in) throws IOException, ApiError {
        byte[] b = in.readNBytes(MAX_BODY + 1);
        if (b.length > MAX_BODY) throw new ApiError(413, "too_large", "Request too large.");
        return b;
    }

    static final class ApiError extends Exception {
        final int status;
        final String code;
        final JsonObject extra;
        ApiError(int status, String code, String m) { this(status, code, m, null); }
        ApiError(int status, String code, String m, JsonObject extra) { super(m); this.status = status; this.code = code; this.extra = extra; }
    }

    @Override
    public void close() {
        http.stop(1);
        pool.shutdownNow();
    }
}
