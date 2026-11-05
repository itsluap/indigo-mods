package online.indigomc.economy.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The authoritative Indigo ledger. Every balance lives here, keyed by Minecraft UUID, and every
 * change is one journal event whose postings sum to zero (double entry). The issuance account
 * {@link #TREASURY} is the only account allowed below zero: its negated balance is the money supply.
 *
 * <p>One lock serializes every mutation, whether it comes from the server thread (commands, UI)
 * or a bridge thread (Discord). An event is committed when its line is fsynced; only then does
 * the in-memory state change and the caller get a result.
 */
public final class Ledger implements AutoCloseable {
    public static final String TREASURY = "system:treasury";
    /** Money in flight inside one shop trade. Zero whenever no trade is executing; open holds are listed. */
    public static final String ESCROW = "system:shop-escrow";
    /** Discord games prize pool (0.4.4): topped up from the treasury once per UTC day, up to the global daily cap. */
    public static final String GAMES = "system:games";
    /** Casino rounds in play (0.5.0): the player's stake plus the house's reserve for the best possible outcome. */
    public static final String CASINO_ESCROW = "system:casino-escrow";
    public static final int FORMAT = 1;
    private static final int HISTORY_PER_ACCOUNT = 200;

    public record Posting(String account, long amount, long before, long after) {}

    public record Tx(long seq, String txid, String ts, String type, String actor, String source, String reason,
                     String idem, List<Posting> postings, JsonObject meta) {
        public Posting postingFor(String account) {
            for (Posting p : postings) if (p.account().equals(account)) return p;
            return null;
        }
    }

    public record Result(Tx tx, boolean replayed) {}

    public record Account(UUID uuid, String name, String created) {}

    public static final class LedgerError extends Exception {
        public final String code;
        public LedgerError(String code, String message) { super(message); this.code = code; }
    }

    private record Idem(String fp, Tx tx) {}

    /** Package-private so {@link CompanyLedger} validates and commits under the very same lock. */
    final ReentrantLock lock = new ReentrantLock(true);
    private final Journal journal;
    private final long startingBalance;
    private final Map<String, Long> balances = new HashMap<>();
    private final Map<UUID, Account> accounts = new LinkedHashMap<>();
    private final Map<String, UUID> byName = new HashMap<>();
    private final Map<String, Idem> idempotency = new HashMap<>();
    private final Map<UUID, String> discordByUuid = new HashMap<>();
    private final Map<String, UUID> uuidByDiscord = new HashMap<>();
    private final Map<String, ArrayDeque<Tx>> history = new HashMap<>();
    /** Company identity, members, invoices, payroll and shop assignments: zero-posting events in this same journal. */
    final Companies companies = new Companies();
    /** hold id -> the transaction that opened it (payer, amount, trade metadata). */
    private final Map<String, Tx> openHolds = new LinkedHashMap<>();
    private long lastCheckpointSeq = 0;
    private final List<Tx> unsavedSettles = new ArrayList<>();
    private String gamesDay, budgetDay;
    private final Map<String, Casino> casinos = new LinkedHashMap<>();
    private final Map<UUID, String> casinoByOwner = new HashMap<>();
    /** round id -> the round's state: casino, player, stake, reserve (summed over open + raises). */
    private final Map<String, JsonObject> openRounds = new LinkedHashMap<>();
    private final Set<UUID> casinoAcked = new HashSet<>();
    private final Map<UUID, Long> gamesToday = new HashMap<>();
    private IOException broken = null;
    private long events = 0;

    public Ledger(Path file, long startingBalance) throws IOException {
        this.journal = new Journal(file);
        this.startingBalance = startingBalance;
        List<Journal.Line> lines = journal.open();
        for (Journal.Line l : lines) apply(l.body());
        events = lines.size();
        if (lines.isEmpty()) {
            try {
                commit(base("genesis", "system", "system", "ledger created", null, null, List.of(), Json.obj("format", FORMAT)));
            } catch (LedgerError e) {
                throw new IOException("could not write the genesis event", e);
            }
        }
        checkInvariant();
    }

    public static String acct(UUID uuid) { return "mc:" + uuid; }
    public static String casinoAcct(String id) { return "casino:" + id; }

    /** A player-owned casino and its house bank ({@link #casinoAcct}). Totals are all-time, rebuilt from the journal. */
    public record Casino(String id, UUID owner, String name, String opened, boolean open, long rounds, long staked, long returned) {
        Casino withRound(long stake, long ret) { return new Casino(id, owner, name, opened, open, rounds + 1, staked + stake, returned + ret); }
        Casino closed() { return new Casino(id, owner, name, opened, false, rounds, staked, returned); }
    }

    // ---------------------------------------------------------------- mutations

    /** Creates the account on first sight (with the configured starting balance) and records name changes. */
    public Account ensureAccount(UUID uuid, String name, String source) throws LedgerError {
        lock.lock();
        try {
            Account a = accounts.get(uuid);
            if (a == null) {
                List<Posting> postings = new ArrayList<>();
                if (startingBalance > 0) {
                    postings.add(posting(TREASURY, -startingBalance));
                    postings.add(new Posting(acct(uuid), startingBalance, 0, startingBalance));
                }
                commit(base("account_open", "system", source, "first join", null, null, postings,
                    Json.obj("uuid", uuid.toString(), "name", name)));
            } else if (!a.name().equals(name)) {
                commit(base("profile", "system", source, "name changed", null, null, List.of(),
                    Json.obj("uuid", uuid.toString(), "name", name, "old", a.name())));
            }
            return accounts.get(uuid);
        } finally {
            lock.unlock();
        }
    }

    /** Player-to-player transfer. Atomic: both postings commit in one journal line or neither does. */
    public Result transfer(UUID from, UUID to, long amount, String reason, String source, String actor, String idem)
            throws LedgerError {
        lock.lock();
        try {
            String fp = fingerprint("transfer", from, to, amount, reason);
            Result replay = replay(source, idem, fp);
            if (replay != null) return replay;
            checkAmount(amount);
            if (from.equals(to)) throw new LedgerError("same_account", "You can't send money to yourself.");
            requireAccount(from, "sender");
            requireAccount(to, "recipient");
            long have = balances.getOrDefault(acct(from), 0L);
            if (have < amount) throw new LedgerError("insufficient_funds", "Not enough money: balance is " + have + ".");
            long toAfter = balances.getOrDefault(acct(to), 0L) + amount;
            if (toAfter > Money.MAX) throw new LedgerError("overflow", "The recipient's balance would exceed the limit.");
            Tx tx = commit(base("transfer", actor, source, reason, idem, fp,
                List.of(posting(acct(from), -amount), posting(acct(to), amount)),
                Json.obj("from", from.toString(), "to", to.toString())));
            return new Result(tx, false);
        } finally {
            lock.unlock();
        }
    }

    /** Staff adjustment against the treasury; positive grants, negative takes. Always logged with actor and reason. */
    public Result adjust(UUID who, long delta, String reason, String source, String actor, String idem) throws LedgerError {
        lock.lock();
        try {
            if (reason == null || reason.isBlank()) throw new LedgerError("reason_required", "Admin adjustments need a reason.");
            String fp = fingerprint("adjust", who, null, delta, reason);
            Result replay = replay(source, idem, fp);
            if (replay != null) return replay;
            checkAmount(Math.abs(delta));
            requireAccount(who, "account");
            long have = balances.getOrDefault(acct(who), 0L);
            if (have + delta < 0) throw new LedgerError("insufficient_funds", "That would make the balance negative.");
            if (have + delta > Money.MAX) throw new LedgerError("overflow", "The balance would exceed the limit.");
            Tx tx = commit(base("admin_adjust", actor, source, reason, idem, fp,
                List.of(posting(TREASURY, -delta), posting(acct(who), delta)), Json.obj("uuid", who.toString())));
            return new Result(tx, false);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Pays a Discord game prize from {@link #GAMES} to a player. All or nothing, idempotent on (games, idem).
     * Caps are per Minecraft account per UTC day (so relinking Discord cannot reset them), per award, and the pool
     * itself, which the day's first award tops up to {@code globalCap} with a {@code games_budget} event (unused budget
     * does not pile up). Everything here is rebuilt from the journal on start.
     */
    public Result gameAward(UUID who, long amount, String game, String day, long playerCap, long globalCap, long maxAward,
                            String reason, String actor, String idem) throws LedgerError {
        lock.lock();
        try {
            String fp = fingerprint("game_award", who, game, amount, reason);
            Result replay = replay("games", idem, fp);
            if (replay != null) return replay;
            checkAmount(amount);
            requireAccount(who, "player");
            if (amount > maxAward) throw new LedgerError("cap_award", "A single prize can be at most " + maxAward + ".");
            long today = gamesPlayerToday(who, day);
            if (today + amount > playerCap) throw new LedgerError("cap_player", "Daily games limit reached for this player.");
            ensureGamesBudget(day, globalCap);
            if (balances.getOrDefault(GAMES, 0L) < amount) throw new LedgerError("cap_global", "Today's games prize budget is used up.");
            Tx tx = commit(base("game_award", actor, "games", reason, idem, fp,
                List.of(posting(GAMES, -amount), posting(acct(who), amount)),
                Json.obj("uuid", who.toString(), "game", game, "day", day)));
            return new Result(tx, false);
        } finally {
            lock.unlock();
        }
    }

    /** Game prizes this player has received on {@code day} (UTC date string). */
    public long gamesPlayerToday(UUID who, String day) {
        lock.lock();
        try { return day.equals(gamesDay) ? gamesToday.getOrDefault(who, 0L) : 0L; } finally { lock.unlock(); }
    }

    /** What is left in the pool on {@code day}: before the day's top-up this is the full cap. */
    public long gamesPoolRemaining(String day, long globalCap) {
        lock.lock();
        try { return day.equals(budgetDay) ? balances.getOrDefault(GAMES, 0L) : globalCap; } finally { lock.unlock(); }
    }

    private void ensureGamesBudget(String day, long cap) throws LedgerError {
        if (day.equals(budgetDay)) return;
        long delta = cap - balances.getOrDefault(GAMES, 0L);
        commit(base("games_budget", "system", "games", "daily games prize budget " + day, null, null,
            delta == 0 ? List.of() : List.of(posting(TREASURY, -delta), posting(GAMES, delta)), Json.obj("day", day, "cap", cap)));
    }

    public Tx link(UUID uuid, String discordId, String actor, String source) throws LedgerError {
        lock.lock();
        try {
            requireAccount(uuid, "account");
            UUID other = uuidByDiscord.get(discordId);
            if (other != null && !other.equals(uuid))
                throw new LedgerError("discord_already_linked", "That Discord account is already linked to another Minecraft account.");
            if (other != null) throw new LedgerError("already_linked", "Already linked.");
            if (discordByUuid.containsKey(uuid))
                throw new LedgerError("account_already_linked", "This Minecraft account is already linked to a Discord account. Unlink it first.");
            return commit(base("link", actor, source, "discord link", null, null, List.of(),
                Json.obj("uuid", uuid.toString(), "discord", discordId)));
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- casinos (0.5.0, D61)

    /**
     * Opens a casino for {@code owner}: one open casino per player, a unique name, and a one-time licence fee that
     * leaves circulation (to the treasury). The house bank starts empty; the owner funds it with {@link #casinoDeposit}.
     */
    public Casino casinoOpen(UUID owner, String name, long fee, String idem) throws LedgerError {
        lock.lock();
        try {
            String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
            String fp = fingerprint("casino_open", owner, clean, fee);
            Result replay = replay("casino", idem, fp);
            if (replay != null) return casinos.get(replay.tx().meta().get("casino").getAsString());
            requireAccount(owner, "owner");
            if (casinoByOwner.containsKey(owner)) throw new LedgerError("casino_exists", "You already run a casino.");
            if (!clean.matches("[A-Za-z0-9 '&!.-]{3,24}"))
                throw new LedgerError("bad_name", "Casino names are 3-24 letters, numbers, spaces or ' & ! . -");
            for (Casino c : casinos.values())
                if (c.open() && c.name().equalsIgnoreCase(clean)) throw new LedgerError("name_taken", "There is already a casino called " + c.name() + ".");
            if (fee < 0) throw new LedgerError("invalid_amount", "Bad licence fee.");
            if (balances.getOrDefault(acct(owner), 0L) < fee)
                throw new LedgerError("insufficient_funds", "Not enough money for the casino licence.");
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            commit(base("casino_open", "mc:" + owner, "casino", "Casino licence: " + clean, idem, fp,
                fee == 0 ? List.of() : List.of(posting(acct(owner), -fee), posting(TREASURY, fee)),
                Json.obj("casino", id, "owner", owner.toString(), "name", clean, "fee", fee, "uuid", owner.toString())));
            return casinos.get(id);
        } finally {
            lock.unlock();
        }
    }

    /** The owner moves money from their wallet into the house bank. */
    public Result casinoDeposit(UUID owner, long amount, String idem) throws LedgerError {
        return casinoMove(owner, amount, true, idem);
    }

    /** The owner takes money out of the house bank. Money reserved for rounds in play is not in the bank. */
    public Result casinoWithdraw(UUID owner, long amount, String idem) throws LedgerError {
        return casinoMove(owner, amount, false, idem);
    }

    private Result casinoMove(UUID owner, long amount, boolean in, String idem) throws LedgerError {
        lock.lock();
        try {
            String type = in ? "casino_deposit" : "casino_withdraw";
            String fp = fingerprint(type, owner, amount);
            Result replay = replay("casino", idem, fp);
            if (replay != null) return replay;
            checkAmount(amount);
            Casino c = ownedOpenCasino(owner);
            String bank = casinoAcct(c.id());
            if (in && balances.getOrDefault(acct(owner), 0L) < amount) throw new LedgerError("insufficient_funds", "Not enough money in your wallet.");
            if (!in && balances.getOrDefault(bank, 0L) < amount) throw new LedgerError("insufficient_funds", "The house bank doesn't hold that much.");
            Tx tx = commit(base(type, "mc:" + owner, "casino", (in ? "Deposit to " : "Withdrawal from ") + c.name(), idem, fp,
                in ? List.of(posting(acct(owner), -amount), posting(bank, amount)) : List.of(posting(bank, -amount), posting(acct(owner), amount)),
                Json.obj("casino", c.id(), "uuid", owner.toString())));
            return new Result(tx, false);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Starts a round: the player's stake and the house's reserve (enough for the best possible outcome) move into
     * {@link #CASINO_ESCROW} in one event, so a round can always be paid and can never be paid twice. The round id is
     * the idempotency key.
     */
    public Result roundOpen(String roundId, String casinoId, UUID player, long stake, long reserve, String game, JsonObject detail)
            throws LedgerError {
        lock.lock();
        try {
            String fp = fingerprint("round_open", casinoId, player, stake, reserve, game);
            Result replay = replay("casino", "open:" + roundId, fp);
            if (replay != null) return replay;
            checkAmount(stake);
            if (reserve < 0) throw new LedgerError("invalid_amount", "Bad reserve.");
            Casino c = casinos.get(casinoId);
            if (c == null || !c.open()) throw new LedgerError("casino_closed", "This casino is closed.");
            if (c.owner().equals(player)) throw new LedgerError("own_casino", "You can't play at your own casino.");
            requireAccount(player, "player");
            if (balances.getOrDefault(acct(player), 0L) < stake) throw new LedgerError("insufficient_funds", "Not enough Indigo Credits.");
            String bank = casinoAcct(casinoId);
            if (balances.getOrDefault(bank, 0L) < reserve) throw new LedgerError("house_cannot_cover", "The house bank can't cover that bet.");
            JsonObject meta = detail == null ? new JsonObject() : detail.deepCopy();
            meta.addProperty("round", roundId);
            meta.addProperty("casino", casinoId);
            meta.addProperty("player", player.toString());
            meta.addProperty("game", game);
            meta.addProperty("stake", stake);
            meta.addProperty("reserve", reserve);
            List<Posting> ps = new ArrayList<>();
            ps.add(posting(acct(player), -stake));
            if (reserve > 0) ps.add(posting(bank, -reserve));
            ps.add(posting(CASINO_ESCROW, stake + reserve));
            Tx tx = commit(base("casino_round_open", "mc:" + player, "casino", c.name() + ": " + game, "open:" + roundId, fp, ps, meta));
            return new Result(tx, false);
        } finally {
            lock.unlock();
        }
    }

    /** Adds stake (and reserve) to a round in play, e.g. a blackjack double. */
    public Result roundRaise(String roundId, long extraStake, long extraReserve) throws LedgerError {
        lock.lock();
        try {
            String fp = fingerprint("round_raise", roundId, extraStake, extraReserve);
            Result replay = replay("casino", "raise:" + roundId, fp);
            if (replay != null) return replay;
            JsonObject r = openRounds.get(roundId);
            if (r == null) throw new LedgerError("no_such_round", "That round is not in play.");
            checkAmount(extraStake);
            if (extraReserve < 0) throw new LedgerError("invalid_amount", "Bad reserve.");
            UUID player = UUID.fromString(r.get("player").getAsString());
            String bank = casinoAcct(r.get("casino").getAsString());
            if (balances.getOrDefault(acct(player), 0L) < extraStake) throw new LedgerError("insufficient_funds", "Not enough Indigo Credits.");
            if (balances.getOrDefault(bank, 0L) < extraReserve) throw new LedgerError("house_cannot_cover", "The house bank can't cover that.");
            List<Posting> ps = new ArrayList<>();
            ps.add(posting(acct(player), -extraStake));
            if (extraReserve > 0) ps.add(posting(bank, -extraReserve));
            ps.add(posting(CASINO_ESCROW, extraStake + extraReserve));
            Tx tx = commit(base("casino_round_raise", "mc:" + player, "casino", "Raise", "raise:" + roundId, fp, ps,
                Json.obj("round", roundId, "casino", r.get("casino").getAsString(), "player", player.toString(),
                    "stake", extraStake, "reserve", extraReserve)));
            return new Result(tx, false);
        } finally {
            lock.unlock();
        }
    }

    /** Ends a round: the player gets {@code returned} (0 up to everything in escrow for it), the house bank the rest. */
    public Result roundSettle(String roundId, long returned, String outcome) throws LedgerError {
        return roundClose(roundId, returned, outcome, false);
    }

    /** Cancels a round that could not finish (crash recovery): stakes back to the player, reserve back to the bank. */
    public Result roundVoid(String roundId, String why) throws LedgerError {
        return roundClose(roundId, -1, why, true);
    }

    private Result roundClose(String roundId, long returned, String outcome, boolean voided) throws LedgerError {
        lock.lock();
        try {
            String fp = fingerprint("round_close", roundId, voided);
            Result replay = replay("casino", "close:" + roundId, fp);
            if (replay != null) return replay;
            JsonObject r = openRounds.get(roundId);
            if (r == null) throw new LedgerError("no_such_round", "That round is not in play.");
            long stake = r.get("stake").getAsLong(), reserve = r.get("reserve").getAsLong(), total = stake + reserve;
            long toPlayer = voided ? stake : returned;
            if (toPlayer < 0 || toPlayer > total) throw new LedgerError("invalid_amount", "A round can't return more than is in play.");
            UUID player = UUID.fromString(r.get("player").getAsString());
            String casinoId = r.get("casino").getAsString();
            List<Posting> ps = new ArrayList<>();
            ps.add(posting(CASINO_ESCROW, -total));
            if (toPlayer > 0) ps.add(posting(acct(player), toPlayer));
            if (total - toPlayer > 0) ps.add(posting(casinoAcct(casinoId), total - toPlayer));
            JsonObject meta = Json.obj("round", roundId, "casino", casinoId, "player", player.toString(), "game", r.get("game").getAsString(),
                "stake", stake, "returned", toPlayer, "outcome", clean(outcome));
            Tx tx = commit(base(voided ? "casino_round_void" : "casino_round_settle", "system", "casino",
                casinos.get(casinoId).name() + ": " + r.get("game").getAsString() + " " + clean(outcome), "close:" + roundId, fp, ps, meta));
            return new Result(tx, false);
        } finally {
            lock.unlock();
        }
    }

    /** Closes the owner's casino: no rounds may be in play; the whole house bank goes back to the owner. The licence is not refunded. */
    public Result casinoClose(UUID owner, String idem) throws LedgerError {
        lock.lock();
        try {
            String fp = fingerprint("casino_close", owner);
            Result replay = replay("casino", idem, fp);
            if (replay != null) return replay;
            Casino c = ownedOpenCasino(owner);
            for (JsonObject r : openRounds.values())
                if (r.get("casino").getAsString().equals(c.id())) throw new LedgerError("rounds_in_play", "A game is still in play at your casino.");
            long bank = balances.getOrDefault(casinoAcct(c.id()), 0L);
            Tx tx = commit(base("casino_close", "mc:" + owner, "casino", "Closed " + c.name(), idem, fp,
                bank == 0 ? List.of() : List.of(posting(casinoAcct(c.id()), -bank), posting(acct(owner), bank)),
                Json.obj("casino", c.id(), "uuid", owner.toString())));
            return new Result(tx, false);
        } finally {
            lock.unlock();
        }
    }

    /** Records that a player confirmed the casino notice (play money, meant for grown-ups). Asked once per player. */
    public void casinoAck(UUID player) throws LedgerError {
        lock.lock();
        try {
            if (casinoAcked.contains(player)) return;
            requireAccount(player, "player");
            commit(base("casino_ack", "mc:" + player, "casino", "casino notice confirmed", null, null, List.of(), Json.obj("uuid", player.toString())));
        } finally {
            lock.unlock();
        }
    }

    private Casino ownedOpenCasino(UUID owner) throws LedgerError {
        String id = casinoByOwner.get(owner);
        if (id == null) throw new LedgerError("no_casino", "You don't run a casino. Open one with /casino open <name>.");
        return casinos.get(id);
    }

    public Optional<Casino> casino(String id) { lock.lock(); try { return Optional.ofNullable(casinos.get(id)); } finally { lock.unlock(); } }
    public Optional<Casino> casinoOf(UUID owner) {
        lock.lock();
        try { String id = casinoByOwner.get(owner); return Optional.ofNullable(id == null ? null : casinos.get(id)); } finally { lock.unlock(); }
    }
    public List<Casino> casinos() { lock.lock(); try { return new ArrayList<>(casinos.values()); } finally { lock.unlock(); } }
    public long casinoBank(String id) { lock.lock(); try { return balances.getOrDefault(casinoAcct(id), 0L); } finally { lock.unlock(); } }
    public boolean casinoAcked(UUID player) { lock.lock(); try { return casinoAcked.contains(player); } finally { lock.unlock(); } }
    public List<JsonObject> openRounds() {
        lock.lock();
        try { List<JsonObject> out = new ArrayList<>(); for (JsonObject r : openRounds.values()) out.add(r.deepCopy()); return out; }
        finally { lock.unlock(); }
    }


    // ---------------------------------------------------------------- shop holds

    /**
     * Moves a buyer's payment into escrow for one shop trade. Atomic: the whole amount or nothing
     * (never a partial debit). The hold id is also the idempotency key, so a replayed request cannot
     * charge twice.
     */
    public Result hold(String holdId, UUID payer, long amount, String reason, JsonObject tradeMeta) throws LedgerError {
        lock.lock();
        try {
            String fp = fingerprint("hold", payer, null, amount, holdId);
            Result replay = replay("shop", "hold:" + holdId, fp);
            if (replay != null) return replay;
            checkAmount(amount);
            requireAccount(payer, "buyer");
            if (balances.getOrDefault(acct(payer), 0L) < amount)
                throw new LedgerError("insufficient_funds", "Not enough Indigo Credits.");
            JsonObject meta = tradeMeta == null ? new JsonObject() : tradeMeta.deepCopy();
            meta.addProperty("hold", holdId);
            meta.addProperty("payer", payer.toString());
            Tx tx = commit(base("shop_hold", "mc:" + payer, "shop", reason, "hold:" + holdId, fp,
                List.of(posting(acct(payer), -amount), posting(ESCROW, amount)), meta));
            return new Result(tx, false);
        } finally {
            lock.unlock();
        }
    }

    /** Pays an open hold out to the shop owner (online or not). Idempotent per hold. */
    public Result settle(String holdId, UUID owner, String reason) throws LedgerError {
        return closeHold(holdId, owner, "shop_settle", reason);
    }

    /** Returns an open hold to the payer (trade failed after payment, or crash recovery). Idempotent per hold. */
    public Result refund(String holdId, String reason) throws LedgerError {
        return closeHold(holdId, null, "shop_refund", reason);
    }

    /** Closes a hold into the treasury: money spent at an admin (creative) shop leaves circulation. */
    public Result burn(String holdId, String reason) throws LedgerError {
        return closeHold(holdId, null, "shop_burn", reason);
    }

    /**
     * Settles a shop trade, consulting the company shop registry (Indigo companies). A trader assigned to a company
     * pays the company treasury, but only while the assignment is still valid: the company is not dissolved, the
     * trader's current Lightman's Currency owner is the member who assigned it, and that member is still in the
     * company. Otherwise, and for every unassigned trader, this is exactly {@link #settle}: the owner is paid.
     * Decision and posting happen under one lock in one journal line, and the hold id keeps it idempotent.
     */
    public Result settleShop(String holdId, String trader, UUID owner, String reason) throws LedgerError {
        lock.lock();
        try {
            Result replay = replay("shop", "close:" + holdId, fingerprint("close", "shop_settle", holdId));
            if (replay != null) return replay;
            Companies.Shop s = companies.shop(trader).orElse(null);
            if (s == null) return closeHold(holdId, owner, "shop_settle", reason);
            Companies.Company c = companies.get(s.company()).orElse(null);
            String skip = c == null || c.status() == Companies.Status.DISSOLVED ? "company_dissolved"
                : !s.operator().equals(owner) ? "trader_owner_changed"
                : !c.members().containsKey(owner) ? "operator_not_member" : null;
            JsonObject extra = new JsonObject();
            extra.addProperty("company", s.company());
            extra.addProperty("operator", s.operator().toString());
            if (skip != null) {
                extra.addProperty("company_route_skipped", skip);
                return closeHold(holdId, acct(owner), owner, "shop_settle", reason, extra);
            }
            return closeHold(holdId, s.company(), null, "shop_settle", reason, extra);
        } finally {
            lock.unlock();
        }
    }

    private Result closeHold(String holdId, UUID to, String type, String reason) throws LedgerError {
        return closeHold(holdId, to == null ? null : acct(to), to, type, reason, null);
    }

    /** {@code targetAccount} null = back to the payer (refund); {@code mustExist} is a player account that must be open. */
    private Result closeHold(String holdId, String targetAccount, UUID mustExist, String type, String reason, JsonObject extra) throws LedgerError {
        lock.lock();
        try {
            Result replay = replay("shop", "close:" + holdId, fingerprint("close", type, holdId));
            if (replay != null) return replay;
            Tx open = openHolds.get(holdId);
            if (open == null) throw new LedgerError("no_such_hold", "That shop payment is not open.");
            UUID payer = UUID.fromString(open.meta().get("payer").getAsString());
            boolean burn = type.equals("shop_burn");
            long amount = open.postingFor(ESCROW).amount();
            String target;
            String to;
            if (burn) { target = TREASURY; to = TREASURY; }
            else if (targetAccount == null) { requireAccount(payer, "recipient"); target = acct(payer); to = payer.toString(); }
            else if (Companies.isCompanyAccount(targetAccount)) { target = targetAccount; to = targetAccount; }
            else { requireAccount(mustExist, "recipient"); target = targetAccount; to = mustExist.toString(); }
            JsonObject meta = open.meta().deepCopy();
            meta.addProperty("closes", holdId);
            meta.addProperty("to", to);
            if (extra != null) for (var e : extra.entrySet()) meta.add(e.getKey(), e.getValue());
            Tx tx = commit(base(type, "system", "shop", reason, "close:" + holdId, fingerprint("close", type, holdId),
                List.of(posting(ESCROW, -amount), posting(target, amount)), meta));
            return new Result(tx, false);
        } finally {
            lock.unlock();
        }
    }

    public List<Tx> openHolds() { lock.lock(); try { return new ArrayList<>(openHolds.values()); } finally { lock.unlock(); } }

    /** Marks that the world was saved: shop item movements before this point are durable in the world too. */
    public void checkpoint(String what) throws LedgerError {
        lock.lock();
        try {
            if (lastCheckpointSeq == seq()) return;   // nothing happened since the last save
            commit(base("world_saved", "system", "system", what, null, null, List.of(), new JsonObject()));
        } finally {
            lock.unlock();
        }
    }

    /** Shop settlements committed after the last world save (their item movements may not be in the world). */
    public List<Tx> shopTradesSinceLastCheckpoint() {
        lock.lock();
        try {
            return new ArrayList<>(unsavedSettles);
        } finally {
            lock.unlock();
        }
    }

    public long lastCheckpointSeq() { lock.lock(); try { return lastCheckpointSeq; } finally { lock.unlock(); } }

    public Tx unlink(UUID uuid, String actor, String source, String reason) throws LedgerError {
        lock.lock();
        try {
            String d = discordByUuid.get(uuid);
            if (d == null) throw new LedgerError("not_linked", "No Discord account is linked.");
            return commit(base("unlink", actor, source, reason, null, null, List.of(),
                Json.obj("uuid", uuid.toString(), "discord", d)));
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- queries

    public long balance(UUID uuid) { lock.lock(); try { return balances.getOrDefault(acct(uuid), 0L); } finally { lock.unlock(); } }
    public long accountBalance(String account) { lock.lock(); try { return balances.getOrDefault(account, 0L); } finally { lock.unlock(); } }
    public long treasury() { lock.lock(); try { return balances.getOrDefault(TREASURY, 0L); } finally { lock.unlock(); } }
    public Optional<Account> account(UUID uuid) { lock.lock(); try { return Optional.ofNullable(accounts.get(uuid)); } finally { lock.unlock(); } }
    public Optional<Account> byName(String name) {
        lock.lock();
        try { UUID u = byName.get(name.toLowerCase(Locale.ROOT)); return Optional.ofNullable(u == null ? null : accounts.get(u)); }
        finally { lock.unlock(); }
    }
    public List<String> names() { lock.lock(); try { return accounts.values().stream().map(Account::name).sorted(String.CASE_INSENSITIVE_ORDER).toList(); } finally { lock.unlock(); } }
    public Optional<UUID> uuidForDiscord(String id) { lock.lock(); try { return Optional.ofNullable(uuidByDiscord.get(id)); } finally { lock.unlock(); } }
    public Optional<String> discordFor(UUID uuid) { lock.lock(); try { return Optional.ofNullable(discordByUuid.get(uuid)); } finally { lock.unlock(); } }
    /** A human label for any account: a player's name, a company's name, or "Indigo" for system accounts. */
    public String displayName(String account) {
        lock.lock();
        try {
            if (account.startsWith("mc:")) {
                Account a = accounts.get(UUID.fromString(account.substring(3)));
                return a == null ? "?" : a.name();
            }
            if (Companies.isCompanyAccount(account)) return companies.get(account).map(Companies.Company::name).orElse("?");
            return "Indigo";
        } finally {
            lock.unlock();
        }
    }

    /** Read-only company state. Mutations go through {@link CompanyLedger}. */
    public Companies companies() { return companies; }
    public long seq() { lock.lock(); try { return journal.nextSeq() - 1; } finally { lock.unlock(); } }
    public int accountCount() { lock.lock(); try { return accounts.size(); } finally { lock.unlock(); } }
    public boolean available() { return broken == null; }
    public String tornNote() { return journal.tornNote(); }
    public Path file() { return journal.file(); }

    /** Newest first. Includes money-moving and account events that touched this account. */
    public List<Tx> history(UUID uuid, int limit) { return history(acct(uuid), limit); }

    /** Newest first, for any account id: {@code mc:<uuid>}, {@code company:<uuid>} or a system account. */
    public List<Tx> history(String account, int limit) {
        lock.lock();
        try {
            ArrayDeque<Tx> d = history.getOrDefault(account, new ArrayDeque<>());
            List<Tx> out = new ArrayList<>();
            Iterator<Tx> it = d.descendingIterator();
            while (it.hasNext() && out.size() < limit) out.add(it.next());
            return out;
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Long> balancesSnapshot() { lock.lock(); try { return new TreeMap<>(balances); } finally { lock.unlock(); } }

    /** Re-reads the journal from disk with a fresh parser and compares the replayed balances to memory. */
    public String verify() throws IOException, LedgerError {
        lock.lock();
        try {
            Ledger fresh = new Ledger(journal.file(), startingBalance, true);
            if (!fresh.balances.equals(balances)) throw new LedgerError("mismatch", "replayed balances differ from memory");
            if (!fresh.companies.digest().equals(companies.digest())) throw new LedgerError("mismatch", "replayed company state differs from memory");
            checkInvariant();
            long sum = 0;
            for (long v : balances.values()) sum += v;
            return "seq=" + seq() + " accounts=" + accounts.size() + " sum=" + sum + " supply=" + (-treasury()) + " head=" + journal.lastHash().substring(0, 16);
        } finally {
            lock.unlock();
        }
    }

    private Ledger(Path file, long startingBalance, boolean readOnly) throws IOException {
        this.journal = new Journal(file);   // never opened: this instance only replays
        this.startingBalance = startingBalance;
        byte[] raw = java.nio.file.Files.readAllBytes(file);
        String prev = Journal.GENESIS;
        long seq = 0;
        int n = 0;
        for (String s : new String(raw, StandardCharsets.UTF_8).split("\n")) {
            if (s.isEmpty()) continue;
            Journal.Line l = Journal.parseAndCheck(s, prev, seq + 1, ++n);
            apply(l.body());
            prev = l.hash();
            seq = l.seq();
        }
    }

    // ---------------------------------------------------------------- internals

    Result replay(String source, String idem, String fp) throws LedgerError {
        if (idem == null) return null;
        Idem prior = idempotency.get(source + "|" + idem);
        if (prior == null) return null;
        if (!prior.fp().equals(fp))
            throw new LedgerError("idempotency_conflict", "That request ID was already used for a different request.");
        return new Result(prior.tx(), true);
    }

    void checkAmount(long amount) throws LedgerError {
        if (amount <= 0) throw new LedgerError("invalid_amount", "The amount must be more than zero.");
        if (amount > Money.MAX) throw new LedgerError("invalid_amount", "That amount is too large.");
    }

    void requireAccount(UUID uuid, String role) throws LedgerError {
        if (!accounts.containsKey(uuid)) throw new LedgerError("no_such_account", "No Indigo account for that " + role + ".");
    }

    Posting posting(String account, long amount) {
        long before = balances.getOrDefault(account, 0L);
        return new Posting(account, amount, before, Math.addExact(before, amount));
    }

    JsonObject base(String type, String actor, String source, String reason, String idem, String fp,
                            List<Posting> postings, JsonObject meta) {
        JsonArray ps = new JsonArray();
        long sum = 0;
        for (Posting p : postings) {
            ps.add(Json.obj("account", p.account(), "amount", p.amount(), "before", p.before(), "after", p.after()));
            sum += p.amount();
        }
        if (sum != 0) throw new IllegalStateException("unbalanced postings");
        return Json.obj("seq", 0L, "prev", "", "txid", UUID.randomUUID().toString(), "ts", Instant.now().toString(),
            "type", type, "actor", actor, "source", source, "reason", clean(reason), "idem", idem, "fp", fp,
            "postings", ps, "meta", meta);
    }

    Tx commit(JsonObject body) throws LedgerError {
        if (broken != null) throw new LedgerError("ledger_unavailable", "The economy is temporarily unavailable.");
        body.addProperty("seq", journal.nextSeq());
        body.addProperty("prev", journal.lastHash());
        try {
            journal.append(body);
        } catch (IOException e) {
            broken = e;   // fail closed: the file may hold a partial line; restart recovers it
            throw new LedgerError("ledger_unavailable", "The economy is temporarily unavailable.");
        }
        return apply(body);
    }

    private Tx apply(JsonObject b) {
        List<Posting> ps = new ArrayList<>();
        for (JsonElement e : b.getAsJsonArray("postings")) {
            JsonObject p = e.getAsJsonObject();
            String account = p.get("account").getAsString();
            long amount = p.get("amount").getAsLong(), before = p.get("before").getAsLong(), after = p.get("after").getAsLong();
            long have = balances.getOrDefault(account, 0L);
            if (have != before || before + amount != after)
                throw new IllegalStateException("seq " + b.get("seq") + ": posting for " + account + " does not match replayed balance");
            if (after < 0 && !account.equals(TREASURY)) throw new IllegalStateException("seq " + b.get("seq") + ": negative balance");
            balances.put(account, after);
            ps.add(new Posting(account, amount, before, after));
        }
        JsonObject meta = b.has("meta") ? b.getAsJsonObject("meta") : new JsonObject();
        Tx tx = new Tx(b.get("seq").getAsLong(), Json.str(b, "txid"), Json.str(b, "ts"), Json.str(b, "type"),
            Json.str(b, "actor"), Json.str(b, "source"), Json.str(b, "reason"), Json.str(b, "idem"), ps, meta);
        switch (tx.type()) {
            case "account_open" -> {
                UUID u = UUID.fromString(meta.get("uuid").getAsString());
                accounts.put(u, new Account(u, meta.get("name").getAsString(), tx.ts()));
                balances.putIfAbsent(acct(u), 0L);
                byName.put(meta.get("name").getAsString().toLowerCase(Locale.ROOT), u);
            }
            case "profile" -> {
                UUID u = UUID.fromString(meta.get("uuid").getAsString());
                Account old = accounts.get(u);
                byName.remove(old.name().toLowerCase(Locale.ROOT), u);
                accounts.put(u, new Account(u, meta.get("name").getAsString(), old.created()));
                byName.put(meta.get("name").getAsString().toLowerCase(Locale.ROOT), u);
            }
            case "link" -> {
                UUID u = UUID.fromString(meta.get("uuid").getAsString());
                discordByUuid.put(u, meta.get("discord").getAsString());
                uuidByDiscord.put(meta.get("discord").getAsString(), u);
            }
            case "shop_hold" -> openHolds.put(meta.get("hold").getAsString(), tx);
            case "shop_settle", "shop_refund", "shop_burn" -> {
                openHolds.remove(meta.get("closes").getAsString());
                if (tx.type().equals("shop_settle")) unsavedSettles.add(tx);
            }
            case "game_award" -> {
                String day = meta.get("day").getAsString();
                if (!day.equals(gamesDay)) { gamesDay = day; gamesToday.clear(); }
                UUID u = UUID.fromString(meta.get("uuid").getAsString());
                gamesToday.merge(u, tx.postingFor(acct(u)).amount(), Long::sum);
            }
            case "games_budget" -> budgetDay = meta.get("day").getAsString();
            case "casino_open" -> {
                UUID o = UUID.fromString(meta.get("owner").getAsString());
                String id = meta.get("casino").getAsString();
                casinos.put(id, new Casino(id, o, meta.get("name").getAsString(), tx.ts(), true, 0, 0, 0));
                casinoByOwner.put(o, id);
                balances.putIfAbsent(casinoAcct(id), 0L);
            }
            case "casino_close" -> {
                String id = meta.get("casino").getAsString();
                Casino c = casinos.get(id);
                casinos.put(id, c.closed());
                casinoByOwner.remove(c.owner(), id);
            }
            case "casino_round_open" -> openRounds.put(meta.get("round").getAsString(), Json.obj(
                "round", meta.get("round").getAsString(), "casino", meta.get("casino").getAsString(), "player", meta.get("player").getAsString(),
                "game", meta.get("game").getAsString(), "stake", meta.get("stake").getAsLong(), "reserve", meta.get("reserve").getAsLong(),
                "opened", tx.ts()));
            case "casino_round_raise" -> {
                JsonObject r = openRounds.get(meta.get("round").getAsString());
                r.addProperty("stake", r.get("stake").getAsLong() + meta.get("stake").getAsLong());
                r.addProperty("reserve", r.get("reserve").getAsLong() + meta.get("reserve").getAsLong());
            }
            case "casino_round_settle", "casino_round_void" -> {
                openRounds.remove(meta.get("round").getAsString());
                if (tx.type().equals("casino_round_settle")) {
                    String id = meta.get("casino").getAsString();
                    casinos.put(id, casinos.get(id).withRound(meta.get("stake").getAsLong(), meta.get("returned").getAsLong()));
                }
            }
            case "casino_ack" -> casinoAcked.add(UUID.fromString(meta.get("uuid").getAsString()));
            case "world_saved" -> {
                lastCheckpointSeq = tx.seq();
                unsavedSettles.clear();
            }
            case "unlink" -> {
                UUID u = UUID.fromString(meta.get("uuid").getAsString());
                String d = discordByUuid.remove(u);
                if (d != null) uuidByDiscord.remove(d);
            }
            default -> { if (Companies.handles(tx.type())) companies.apply(tx); }
        }
        if (tx.idem() != null) idempotency.put(tx.source() + "|" + tx.idem(), new Idem(Json.str(b, "fp"), tx));
        Set<String> touched = new LinkedHashSet<>();
        for (Posting p : ps) touched.add(p.account());
        if (meta.has("uuid")) touched.add(acct(UUID.fromString(meta.get("uuid").getAsString())));
        if (Companies.handles(tx.type()) && meta.has("company")) touched.add(meta.get("company").getAsString());
        for (String a : touched) {
            ArrayDeque<Tx> d = history.computeIfAbsent(a, k -> new ArrayDeque<>());
            d.addLast(tx);
            if (d.size() > HISTORY_PER_ACCOUNT) d.removeFirst();
        }
        events++;
        return tx;
    }

    private void checkInvariant() {
        long sum = 0;
        for (long v : balances.values()) sum = Math.addExact(sum, v);
        if (sum != 0) throw new IllegalStateException("ledger does not sum to zero: " + sum);
    }

    static String clean(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\p{Cntrl}", " ").strip();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }

    static String fingerprint(Object... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(String.valueOf(Arrays.asList(parts)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try { journal.close(); } finally { lock.unlock(); }
    }
}
