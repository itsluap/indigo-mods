package online.indigomc.economy;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import online.indigomc.economy.core.*;
import online.indigomc.economy.net.WalletPayloads;
import online.indigomc.economy.net.WalletPayloads.Row;
import online.indigomc.economy.net.WalletPayloads.State;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Server-lifetime glue between Minecraft and the MC-independent ledger, link and bridge classes. */
public final class EconomyService {
    private static volatile EconomyService current;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

    public final MinecraftServer server;
    public final EconomyConfig cfg;
    public final Money money;
    public final Ledger ledger;          // null when the journal could not be opened (fail closed)
    public final Links links;
    /** The /company chat commands over the same ledger (null when the journal could not be opened). */
    public final CompanyConsole companies;
    /** Set by the Lightman's Currency integration: which trader a player is looking at (read server-side). */
    public static volatile CompanyConsole.ShopProbe shopProbe = null;
    public final String failure;
    private Bridge bridge;
    private final Map<UUID, ArrayDeque<Long>> recent = new HashMap<>();

    public static EconomyService get() { return current; }

    private EconomyService(MinecraftServer server, EconomyConfig cfg, Ledger ledger, String failure) {
        this.server = server;
        this.cfg = cfg;
        this.money = new Money(cfg.decimals, cfg.singular, cfg.plural);
        this.ledger = ledger;
        this.failure = failure;
        this.links = ledger == null ? null : new Links(ledger, System::currentTimeMillis, cfg.linkTtlSeconds * 1000L, 5, 10, 600_000);
        this.companies = ledger == null ? null : new CompanyConsole(ledger, money, System::currentTimeMillis, p -> {
            CompanyConsole.ShopProbe probe = shopProbe;
            return probe == null ? Optional.empty() : probe.lookedAt(p);
        });
    }

    static void start(MinecraftServer server) {
        Path journal = server.getWorldPath(LevelResource.ROOT).resolve("indigo_economy/journal.jsonl").toAbsolutePath().normalize();
        EconomyConfig cfg;
        try {
            cfg = EconomyConfig.load(FMLPaths.CONFIGDIR.get());
        } catch (Exception e) {
            IndigoEconomy.LOG.error("INDIGO_ECONOMY_UNAVAILABLE config: {}", e.toString());
            current = new EconomyService(server, null, null, "config: " + e.getMessage());
            return;
        }
        Ledger ledger = null;
        String failure = null;
        try {
            ledger = new Ledger(journal, cfg.startingBalance);
        } catch (Exception e) {
            failure = e.getMessage();
            IndigoEconomy.LOG.error("INDIGO_ECONOMY_UNAVAILABLE journal {} could not be opened: {} (economy disabled, world unaffected)", journal, e.toString());
        }
        EconomyService s = new EconomyService(server, cfg, ledger, failure);
        current = s;
        if (ledger == null) return;
        if (ledger.tornNote() != null) IndigoEconomy.LOG.warn("INDIGO_ECONOMY_TORN_TAIL {}", ledger.tornNote());
        if (cfg.bridgeEnabled) {
            try {
                s.bridge = new Bridge(ledger, s.links, s.money, cfg.secret(FMLPaths.CONFIGDIR.get()), s.bridgeEvents(),
                    System::currentTimeMillis, cfg.bridgeBind, cfg.bridgePort, cfg.bridgeRate, server);
                long unit = (long) Math.pow(10, cfg.decimals);
                s.bridge.games(new Bridge.GamesPolicy(cfg.gamesEnabled, cfg.gamesPlayerDailyIc * unit, cfg.gamesGlobalDailyIc * unit,
                    cfg.gamesMaxAwardIc * unit));
                IndigoEconomy.LOG.info("INDIGO_ECONOMY_BRIDGE listening on {}:{}", cfg.bridgeBind, s.bridge.port());
                IndigoEconomy.LOG.info("INDIGO_ECONOMY_GAMES enabled={} player_daily={} global_daily={} max_award={}", cfg.gamesEnabled,
                    cfg.gamesPlayerDailyIc, cfg.gamesGlobalDailyIc, cfg.gamesMaxAwardIc);
                s.bridge.companies(cfg.companiesEnabled);
                s.bridge.admin(new Bridge.AdminPolicy(cfg.adminAdjustEnabled, cfg.adminAdjustMaxIc * unit));
                IndigoEconomy.LOG.info("INDIGO_ECONOMY_ADMIN_ADJUST enabled={} max_ic={}", cfg.adminAdjustEnabled, cfg.adminAdjustMaxIc);
            } catch (Exception e) {
                IndigoEconomy.LOG.error("INDIGO_ECONOMY_BRIDGE_FAILED {} (in-game economy keeps working)", e.toString());
            }
        }
        s.recoverShops();
        online.indigomc.economy.casino.CasinoService.recover(s);
        IndigoEconomy.LOG.info("INDIGO_ECONOMY_CASINOS enabled={} licence={} max_bet={} open={}", cfg.casinoEnabled, cfg.casinoLicenceIc,
            cfg.casinoMaxBetIc, ledger.casinos().stream().filter(Ledger.Casino::open).count());
        IndigoEconomy.LOG.info("INDIGO_ECONOMY_READY seq={} accounts={} supply={} companies={} companies_enabled={} journal={}", ledger.seq(),
            ledger.accountCount(), -ledger.treasury(), ledger.companies().all().size(), cfg.companiesEnabled, journal);
    }

    static void stop() {
        EconomyService s = current;
        current = null;
        if (s == null) return;
        if (s.bridge != null) s.bridge.close();
        if (s.ledger != null) {
            try { s.ledger.close(); } catch (Exception e) { IndigoEconomy.LOG.warn("closing journal: {}", e.toString()); }
            IndigoEconomy.LOG.info("INDIGO_ECONOMY_STOPPED seq={}", s.ledger.seq());
        }
    }

    public String ttlText() {
        int t = cfg.linkTtlSeconds;
        return t < 120 ? t + " seconds" : (t / 60) + " minutes";
    }

    /** Start-up: refund shop payments left open by a crash; report shop sales newer than the last world save. */
    void recoverShops() {
        for (Ledger.Tx t : ledger.openHolds()) {
            String hold = t.meta().get("hold").getAsString();
            try {
                ledger.refund(hold, "Crash recovery: shop trade never finished");
                IndigoEconomy.LOG.warn("INDIGO_ECONOMY_HOLD_REFUNDED hold={} payer={}", hold, t.meta().get("payer").getAsString());
            } catch (Ledger.LedgerError e) {
                IndigoEconomy.LOG.error("INDIGO_ECONOMY_HOLD_REFUND_FAILED hold={} code={}", hold, e.code);
            }
        }
        List<Ledger.Tx> unsaved = ledger.shopTradesSinceLastCheckpoint();
        if (unsaved.isEmpty()) return;
        com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
        for (Ledger.Tx t : unsaved)
            rows.add(Json.obj("seq", t.seq(), "txid", t.txid(), "ts", t.ts(), "payer", Json.str(t.meta(), "payer"), "to", Json.str(t.meta(), "to"),
                "amount_minor", -t.postingFor(Ledger.ESCROW).amount(), "item", Json.str(t.meta(), "item"), "shop", Json.str(t.meta(), "shop")));
        java.nio.file.Path f = ledger.file().resolveSibling("unsaved-shop-trades-" + System.currentTimeMillis() + ".json");
        try {
            java.nio.file.Files.writeString(f, Json.GSON.toJson(rows));
        } catch (java.io.IOException e) {
            IndigoEconomy.LOG.error("could not write {}", f);
        }
        IndigoEconomy.LOG.warn("INDIGO_ECONOMY_UNSAVED_SHOP_TRADES n={} report={} (money moved but the world may have rolled back the goods; staff review)",
            unsaved.size(), f.getFileName());
    }

    void worldSaved() {
        if (!ok()) return;
        try {
            ledger.checkpoint("world saved");
        } catch (Ledger.LedgerError e) {
            IndigoEconomy.LOG.warn("checkpoint: {}", e.code);
        }
    }

    public boolean ok() { return ledger != null && ledger.available(); }

    public Component unavailable() {
        return Component.literal("The Indigo economy is unavailable right now. Staff have been told.").withStyle(ChatFormatting.RED);
    }

    // ---------------------------------------------------------------- events

    /** A real login: outdated clients (no wallet mod, e.g. the 0.3.0 pack) are told to update, then the account is ensured. */
    void onLogin(ServerPlayer p) {
        if (cfg != null && cfg.requireClientMod && !hasUi(p)) {
            IndigoEconomy.LOG.info("INDIGO_ECONOMY_OUTDATED_CLIENT {} refused (no indigo_economy on the client)", p.getGameProfile().getName());
            p.connection.disconnect(Component.literal("Indigo has updated to pack " + cfg.packVersion + ".\n\n")
                .append(Component.literal("Update the Indigo pack in your launcher (or download it again from www.indigomc.online/setup), then rejoin.")));
            return;
        }
        onJoin(p);
    }

    public void onJoin(ServerPlayer p) {
        if (ledger == null) return;
        try {
            ledger.ensureAccount(p.getUUID(), p.getGameProfile().getName(), "minecraft");
            push(p, false, State.NONE, "", "", 0);   // shop screens need the balance before the wallet is opened
        } catch (Ledger.LedgerError e) {
            IndigoEconomy.LOG.error("INDIGO_ECONOMY account for {}: {}", p.getUUID(), e.code);
        }
    }

    private Bridge.Events bridgeEvents() {
        return new Bridge.Events() {
            @Override public void transfer(Ledger.Tx tx, UUID from, UUID to) {
                server.execute(() -> announce(tx, from, to, true));
            }
            @Override public void gameAward(Ledger.Tx tx, UUID to, String game) {
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayer(to);
                    if (p == null) return;
                    String amt = money.format(tx.postingFor(Ledger.acct(to)).amount());
                    p.sendSystemMessage(Component.literal("You won " + amt + " in a Discord game (" + game + ").").withStyle(ChatFormatting.GREEN));
                    push(p, false, State.INFO, "Won " + amt + " in a Discord game.", "", 0);
                });
            }
            @Override public void adminAdjust(Ledger.Tx tx, UUID who, long delta, String reason) {
                IndigoEconomy.LOG.info("INDIGO_ECONOMY_ADJUST tx={} player={} delta={} actor={} reason={}", tx.txid(),
                    ledger.account(who).map(Ledger.Account::name).orElse("?"), delta, tx.actor(), reason);
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayer(who);
                    if (p == null) return;
                    String amt = money.format(Math.abs(delta));
                    p.sendSystemMessage(Component.literal((delta > 0 ? "Staff granted you " : "Staff took ") + amt + ": " + reason)
                        .withStyle(delta > 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
                    push(p, false, State.INFO, "Staff adjustment " + money.signed(delta) + ": " + reason, "", 0);
                });
            }
            @Override public void companyPayment(Ledger.Tx tx) {
                server.execute(() -> {
                    String to = Json.str(tx.meta(), "to");
                    if (to == null || !to.startsWith("mc:")) return;
                    ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(to.substring(3)));
                    if (p == null) return;
                    String amt = money.format(tx.postingFor(to).amount());
                    String from = ledger.displayName(Json.str(tx.meta(), "from"));
                    p.sendSystemMessage(Component.literal("You received " + amt + " from " + from + " (via Discord)"
                        + (tx.reason() != null && !tx.reason().isBlank() ? ": " + tx.reason() : ".")).withStyle(ChatFormatting.GREEN));
                    push(p, false, State.INFO, "Received " + amt + " from " + from + ".", "", 0);
                });
            }
            @Override public void linked(UUID uuid, boolean linked) {
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                    if (p == null) return;
                    p.sendSystemMessage(Component.literal(linked ? "Your Discord account is now linked to your Indigo wallet."
                        : "Your Discord account was unlinked from your Indigo wallet.").withStyle(ChatFormatting.AQUA));
                    push(p, false, State.INFO, linked ? "Discord linked." : "Discord unlinked.", "", 0);
                });
            }
        };
    }

    /** Tells the online parties about a committed transfer and refreshes open wallets. Server thread only. */
    void announce(Ledger.Tx tx, UUID from, UUID to, boolean viaDiscord) {
        Ledger.Posting in = tx.postingFor(Ledger.acct(to));
        String amt = money.format(in.amount());
        ServerPlayer r = server.getPlayerList().getPlayer(to);
        if (r != null) {
            r.sendSystemMessage(Component.literal("You received " + amt + " from " + name(from) + (viaDiscord ? " (via Discord)" : "")
                + (tx.reason() != null && !tx.reason().isBlank() ? ": " + tx.reason() : ".")).withStyle(ChatFormatting.GREEN));
            push(r, false, State.INFO, "Received " + amt + " from " + name(from) + ".", "", 0);
        }
        ServerPlayer s = server.getPlayerList().getPlayer(from);
        if (s != null && viaDiscord) {
            s.sendSystemMessage(Component.literal("You sent " + amt + " to " + name(to) + " from Discord.").withStyle(ChatFormatting.YELLOW));
            push(s, false, State.INFO, "Sent " + amt + " to " + name(to) + " from Discord.", "", 0);
        }
    }

    String name(UUID u) { return ledger.account(u).map(Ledger.Account::name).orElse("someone"); }

    // ---------------------------------------------------------------- actions shared by UI and commands

    public record Outcome(boolean ok, String message, Ledger.Tx tx, boolean replayed) {}

    public Outcome send(ServerPlayer from, String toName, String amountText, String note, String source, String idem) {
        if (!ok()) return new Outcome(false, "The economy is unavailable right now.", null, false);
        Optional<Ledger.Account> to = ledger.byName(toName == null ? "" : toName.trim());
        if (to.isEmpty()) return new Outcome(false, "No Indigo account called \"" + toName + "\". They need to have joined once.", null, false);
        long amount;
        try {
            amount = money.parse(amountText);
        } catch (IllegalArgumentException e) {
            return new Outcome(false, e.getMessage(), null, false);
        }
        try {
            Ledger.Result r = ledger.transfer(from.getUUID(), to.get().uuid(), amount, note == null || note.isBlank() ? null : note,
                source, "mc:" + from.getUUID(), idem);
            if (!r.replayed()) announce(r.tx(), from.getUUID(), to.get().uuid(), false);
            String msg = r.replayed() ? "Already sent (that request was only processed once)."
                : "Sent " + money.format(amount) + " to " + to.get().name() + ".";
            IndigoEconomy.LOG.info("INDIGO_ECONOMY_TRANSFER tx={} from={} to={} amount={} source={} replayed={}", r.tx().txid(),
                from.getGameProfile().getName(), to.get().name(), amount, source, r.replayed());
            return new Outcome(true, msg, r.tx(), r.replayed());
        } catch (Ledger.LedgerError e) {
            String msg = e.code.equals("insufficient_funds") ? "Not enough money. Your balance is " + money.format(ledger.balance(from.getUUID())) + "." : e.getMessage();
            IndigoEconomy.LOG.info("INDIGO_ECONOMY_REJECTED from={} to={} code={}", from.getGameProfile().getName(), toName, e.code);
            return new Outcome(false, msg, null, false);
        }
    }

    /** Per-player limit for the untrusted entry points (wallet packets, chat confirm): 20 actions per 10 s. */
    public boolean allow(UUID u) {
        long now = System.currentTimeMillis();
        synchronized (recent) {
            ArrayDeque<Long> d = recent.computeIfAbsent(u, k -> new ArrayDeque<>());
            while (!d.isEmpty() && d.peekFirst() < now - 10_000) d.removeFirst();
            if (d.size() >= 20) return false;
            d.addLast(now);
            return true;
        }
    }

    // ---------------------------------------------------------------- UI state

    public boolean hasUi(ServerPlayer p) {
        // NeoForge adds this interface to the listener at runtime (patched class)
        return p.connection != null && ((net.neoforged.neoforge.common.extensions.ICommonPacketListener) p.connection).hasChannel(State.TYPE);
    }

    public void push(ServerPlayer p, boolean open, int kind, String msg, String code, int secs) {
        if (!hasUi(p)) return;
        PacketDistributor.sendToPlayer(p, state(p, open, kind, msg, code, secs));
    }

    public State state(ServerPlayer p, boolean open, int kind, String msg, String code, int secs) {
        UUID u = p.getUUID();
        List<Row> rows = new ArrayList<>();
        for (Ledger.Tx tx : ledger.history(u, 24)) {
            if (tx.type().equals("casino_ack") || tx.type().equals("casino_round_raise") && tx.postingFor(Ledger.acct(u)) == null) continue;
            rows.add(row(tx, u));
            if (rows.size() == 12) break;
        }
        List<String> names = new ArrayList<>(ledger.names());
        names.remove(p.getGameProfile().getName());
        if (names.size() > 500) names = names.subList(0, 500);
        // an outstanding link code is never re-sent: the server keeps only its hash
        return new State(open, ledger.balance(u), money.format(ledger.balance(u)), cfg.plural, ledger.discordFor(u).isPresent(), code, secs, kind,
            msg == null ? "" : msg, rows, names);
    }

    Row row(Ledger.Tx tx, UUID u) {
        Ledger.Posting p = tx.postingFor(Ledger.acct(u));
        String when = WHEN.format(Instant.parse(tx.ts()));
        String amount = p == null ? "" : money.signed(p.amount());
        boolean incoming = p != null && p.amount() > 0;
        String after = p == null ? "" : money.plain(p.after());
        String note = tx.reason() == null ? "" : tx.reason();
        String label = switch (tx.type()) {
            case "transfer" -> {
                UUID other = UUID.fromString(tx.meta().get(incoming ? "from" : "to").getAsString());
                yield (incoming ? "From " : "To ") + name(other) + ("discord".equals(tx.source()) ? " (Discord)" : "");
            }
            case "admin_adjust" -> "Staff adjustment";
            case "account_open" -> "Account opened";
            case "link" -> "Discord linked";
            case "unlink" -> "Discord unlinked";
            case "profile" -> "Name changed";
            case "shop_hold" -> "Bought " + Json.str(tx.meta(), "item") + " at " + Json.str(tx.meta(), "shop");
            case "shop_settle" -> "Shop sale: " + Json.str(tx.meta(), "item");
            case "shop_refund" -> "Refund: " + Json.str(tx.meta(), "item");
            case "company_transfer" -> (incoming ? "From " + ledger.displayName(Json.str(tx.meta(), "from")) : "To " + ledger.displayName(Json.str(tx.meta(), "to")))
                + ("discord".equals(tx.source()) ? " (Discord)" : "");
            case "payroll_run" -> "Payroll from " + ledger.displayName(Json.str(tx.meta(), "company")) + " (" + Json.str(tx.meta(), "run") + ")";
            case "invoice_pay" -> "Invoice #" + Json.str(tx.meta(), "number") + (incoming ? " paid" : " to " + ledger.displayName(Json.str(tx.meta(), "to")));
            case "company_dissolve" -> "Payout from " + Json.str(tx.meta(), "name") + " (dissolved)";
            case "casino_open" -> "Casino licence";
            case "casino_deposit" -> "To your house bank";
            case "casino_withdraw" -> "From your house bank";
            case "casino_close" -> "Casino closed: bank returned";
            case "casino_round_open" -> "Bet: " + tx.reason();
            case "casino_round_raise" -> "Doubled down";
            case "casino_round_settle" -> "Casino win: " + Json.str(tx.meta(), "game");
            case "casino_round_void" -> "Casino refund";
            default -> tx.type();
        };
        if (tx.type().equals("transfer") && note.equals("Discord payment")) note = "";
        if (tx.type().equals("account_open") || tx.type().equals("profile") || tx.type().equals("link") || tx.type().startsWith("shop_")
            || tx.type().equals("payroll_run") || tx.type().equals("invoice_pay") || tx.type().equals("company_dissolve")
            || tx.type().startsWith("casino_")) note = "";
        // WalletPayloads.Row carries at most 64 label / 96 note characters; a longer string fails to encode and drops the
        // player's connection at every login (e.g. "Bought <long item> at <long shop name>") (0.5.1)
        if (label.length() > 64) label = label.substring(0, 63) + "…";
        if (note.length() > 96) note = note.substring(0, 95) + "…";
        return new Row(when, label, amount, incoming, after, note);
    }

    public void handleRequest(ServerPlayer p, int action) {
        if (!ok()) { p.sendSystemMessage(unavailable()); return; }
        switch (action) {
            case WalletPayloads.Request.OPEN -> push(p, true, State.NONE, "", "", 0);
            case WalletPayloads.Request.REFRESH -> push(p, false, State.NONE, "", "", 0);
            case WalletPayloads.Request.LINK_CODE -> {
                try {
                    Links.Issued c = links.issue(p.getUUID(), p.getGameProfile().getName());
                    IndigoEconomy.LOG.info("INDIGO_ECONOMY_LINK_CODE issued for {} (code not logged)", p.getGameProfile().getName());
                    push(p, false, State.INFO, "Code ready. It works once and expires in " + ttlText() + ".",
                        c.code(), cfg.linkTtlSeconds);
                } catch (Ledger.LedgerError e) {
                    push(p, false, State.ERROR, e.getMessage(), "", 0);
                }
            }
            case WalletPayloads.Request.UNLINK -> {
                try {
                    ledger.unlink(p.getUUID(), "mc:" + p.getUUID(), "minecraft", "unlinked in game");
                    push(p, false, State.OK, "Discord unlinked. Make a new code to link again.", "", 0);
                } catch (Ledger.LedgerError e) {
                    push(p, false, State.ERROR, e.getMessage(), "", 0);
                }
            }
            default -> { }
        }
    }

    public Outcome handleTransfer(ServerPlayer p, WalletPayloads.Transfer t) {
        Outcome o;
        if (!t.requestId().matches("[0-9a-f\\-]{36}")) o = new Outcome(false, "Bad request.", null, false);
        else if (!allow(p.getUUID())) o = new Outcome(false, "Slow down a little and try again.", null, false);
        else o = send(p, t.to(), t.amount(), t.note(), "minecraft", "ui:" + t.requestId());
        push(p, false, o.ok() ? State.OK : State.ERROR, o.message(), "", 0);
        return o;
    }
}
