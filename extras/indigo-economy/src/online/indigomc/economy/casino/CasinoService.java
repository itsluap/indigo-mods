package online.indigomc.economy.casino;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import online.indigomc.economy.EconomyService;
import online.indigomc.economy.IndigoEconomy;
import online.indigomc.economy.core.CasinoGames;
import online.indigomc.economy.core.CasinoGames.Bet;
import online.indigomc.economy.core.CasinoGames.Game;
import online.indigomc.economy.core.Json;
import online.indigomc.economy.core.Ledger;
import online.indigomc.economy.net.CasinoPayloads.Action;
import online.indigomc.economy.net.CasinoPayloads.View;

import java.security.SecureRandom;
import java.util.*;

/**
 * Player-run casinos (0.5.0, decision D61). Server thread only.
 *
 * <p>Every round goes through the ledger: {@code roundOpen} moves the player's stake and the house's reserve (enough
 * for the best possible outcome) into escrow, {@code roundSettle} pays the player their return and the house the rest.
 * Slots and roulette open and settle in the same call. A blackjack hand stays open between clicks; if the player leaves
 * or the server stops, the hand is finished by standing (never refunded, so leaving can't undo a bad hand). Rounds a
 * crash left open are voided (stake back, reserve back) on the next start.
 */
public final class CasinoService {
    private CasinoService() {}

    private static final SecureRandom RNG = new SecureRandom();
    private static final double REACH = 8.0;

    /** An open blackjack hand, keyed by player. */
    private record Hand(String round, BlockPos pos, String casino, long stake, CasinoGames.Blackjack game) {}

    private static final Map<UUID, Hand> HANDS = new HashMap<>();

    // ---------------------------------------------------------------- world hooks

    static void placed(ServerPlayer p, CasinoTableEntity t, Game game) {
        t.placer = p.getUUID();
        EconomyService s = EconomyService.get();
        if (s == null || !s.ok()) return;
        long cap = s.cfg.casinoMaxBetIc * unit(s);
        t.limits(Math.min(100, cap), Math.min(1000, cap));
        Optional<Ledger.Casino> c = s.ledger.casinoOf(p.getUUID());
        if (c.isPresent()) {
            t.bind(c.get().id());
            p.displayClientMessage(Component.literal(title(game) + " added to " + c.get().name() + ". Right-click it to set the bet limits."), false);
        } else {
            p.displayClientMessage(Component.literal("Open a casino first (/casino open <name>), then right-click this "
                + title(game).toLowerCase(Locale.ROOT) + " to add it."), false);
        }
        t.setChanged();
    }

    static boolean mayRemove(ServerPlayer p, CasinoTableEntity t) {
        if (p.hasPermissions(3)) return true;
        if (t.placer != null && t.placer.equals(p.getUUID())) return true;
        EconomyService s = EconomyService.get();
        if (s == null || !s.ok() || t.casinoId.isEmpty()) return t.placer == null;
        return s.ledger.casino(t.casinoId).map(c -> c.owner().equals(p.getUUID())).orElse(true);
    }

    static void removed(Level level, BlockPos pos) {
        for (Map.Entry<UUID, Hand> e : new ArrayList<>(HANDS.entrySet()))
            if (e.getValue().pos.equals(pos)) finishByStanding(e.getKey(), "table removed");
    }

    /** Server start: rounds a crash left open are voided (stake and reserve go back). */
    public static void recover(EconomyService s) {
        HANDS.clear();
        for (JsonObject r : s.ledger.openRounds()) {
            String id = r.get("round").getAsString();
            try {
                s.ledger.roundVoid(id, "crash recovery");
                IndigoEconomy.LOG.warn("INDIGO_CASINO_ROUND_VOIDED round={} player={} stake={}", id, r.get("player").getAsString(), r.get("stake").getAsLong());
            } catch (Ledger.LedgerError e) {
                IndigoEconomy.LOG.error("INDIGO_CASINO_ROUND_VOID_FAILED round={} code={}", id, e.code);
            }
        }
    }

    /** Player left or server stopping: a hand in play is finished by standing and settled normally. */
    public static void playerLeft(UUID u) { if (HANDS.containsKey(u)) finishByStanding(u, "player left"); }

    public static void serverStopping() { for (UUID u : new ArrayList<>(HANDS.keySet())) finishByStanding(u, "server stopping"); }

    private static void finishByStanding(UUID u, String why) {
        Hand h = HANDS.remove(u);
        EconomyService s = EconomyService.get();
        if (h == null || s == null || !s.ok()) return;
        h.game.stand();
        settle(s, h.round, h.game.returned(h.stake), h.game.outcome + " (" + why + ")");
    }

    // ---------------------------------------------------------------- right-click

    static void use(ServerPlayer p, CasinoTableEntity t, Game game) {
        EconomyService s = EconomyService.get();
        if (s == null || !s.ok()) { p.sendSystemMessage(Component.literal("The Indigo economy is unavailable right now.")); return; }
        if (!s.cfg.casinoEnabled) { p.displayClientMessage(Component.literal(CasinoCommands.OFF), true); return; }
        if (!s.hasUi(p)) { p.displayClientMessage(Component.literal("Update the Indigo pack to play."), true); return; }
        Optional<Ledger.Casino> c = t.casinoId.isEmpty() ? Optional.empty() : s.ledger.casino(t.casinoId).filter(Ledger.Casino::open);
        if (c.isEmpty()) {
            Optional<Ledger.Casino> mine = s.ledger.casinoOf(p.getUUID());
            if (t.placer != null && t.placer.equals(p.getUUID()) && mine.isPresent()) {
                t.bind(mine.get().id());
                c = mine;
                p.displayClientMessage(Component.literal(title(game) + " added to " + mine.get().name() + "."), true);
            } else {
                p.displayClientMessage(Component.literal(t.placer != null && t.placer.equals(p.getUUID())
                    ? "Open a casino first: /casino open <name>" : "This " + title(game).toLowerCase(Locale.ROOT) + " isn't part of an open casino."), true);
                return;
            }
        }
        if (c.get().owner().equals(p.getUUID())) send(p, true, manage(s, p, t, game, c.get(), View.NONE, ""));
        else if (!s.ledger.casinoAcked(p.getUUID())) send(p, true, base(s, p, t, game, c.get(), View.ACK, View.NONE, ""));
        else send(p, true, play(s, p, t, game, c.get(), View.NONE, ""));
    }

    // ---------------------------------------------------------------- actions from the screen

    public static void action(ServerPlayer p, Action a) {
        EconomyService s = EconomyService.get();
        if (s == null || !s.ok() || !s.cfg.casinoEnabled) return;
        if (p.distanceToSqr(a.pos().getX() + 0.5, a.pos().getY() + 0.5, a.pos().getZ() + 0.5) > REACH * REACH) return;
        if (!(p.level().getBlockEntity(a.pos()) instanceof CasinoTableEntity t) || !(p.level().getBlockState(a.pos()).getBlock() instanceof CasinoBlock b)) return;
        Optional<Ledger.Casino> oc = s.ledger.casino(t.casinoId).filter(Ledger.Casino::open);
        if (oc.isEmpty()) { send(p, false, closeView(a.pos())); return; }
        Ledger.Casino c = oc.get();
        Game game = b.game;
        boolean owner = c.owner().equals(p.getUUID());
        if (a.action() != Action.REFRESH && !s.allow(p.getUUID())) {
            send(p, false, owner ? manage(s, p, t, game, c, View.ERROR, "Slow down a little.") : play(s, p, t, game, c, View.ERROR, "Slow down a little."));
            return;
        }
        try {
            if (owner) { ownerAction(s, p, t, game, c, a); return; }
            switch (a.action()) {
                case Action.REFRESH -> send(p, false, s.ledger.casinoAcked(p.getUUID()) ? play(s, p, t, game, c, View.NONE, "") : base(s, p, t, game, c, View.ACK, View.NONE, ""));
                case Action.ACK -> {
                    s.ledger.casinoAck(p.getUUID());
                    IndigoEconomy.LOG.info("INDIGO_CASINO_ACK {}", p.getGameProfile().getName());
                    send(p, false, play(s, p, t, game, c, View.NONE, ""));
                }
                case Action.SPIN -> { if (acked(s, p, t, game, c) && game == Game.SLOTS) spin(s, p, t, c, a.amount()); }
                case Action.ROULETTE -> { if (acked(s, p, t, game, c) && game == Game.ROULETTE) roulette(s, p, t, c, a.amount(), a.a1(), a.a2()); }
                case Action.DEAL, Action.HIT, Action.STAND, Action.DOUBLE -> { if (acked(s, p, t, game, c) && game == Game.BLACKJACK) blackjack(s, p, t, c, a); }
                default -> { }
            }
        } catch (Ledger.LedgerError e) {
            send(p, false, owner ? manage(s, p, t, game, c, View.ERROR, e.getMessage()) : play(s, p, t, game, c, View.ERROR, e.getMessage()));
        }
    }

    private static boolean acked(EconomyService s, ServerPlayer p, CasinoTableEntity t, Game game, Ledger.Casino c) {
        if (s.ledger.casinoAcked(p.getUUID())) return true;
        send(p, false, base(s, p, t, game, c, View.ACK, View.NONE, ""));
        return false;
    }

    /** Parses and checks a stake against the table's limits, the server cap and the player's wallet. */
    private static long stake(EconomyService s, ServerPlayer p, CasinoTableEntity t, String text) throws Ledger.LedgerError {
        long v;
        try { v = s.money.parse(text); } catch (IllegalArgumentException e) { throw new Ledger.LedgerError("bad_amount", e.getMessage()); }
        long cap = s.cfg.casinoMaxBetIc * unit(s);
        long max = Math.min(t.maxBet, cap);
        if (v < t.minBet) throw new Ledger.LedgerError("below_min", "The minimum bet here is " + s.money.format(t.minBet) + ".");
        if (v > max) throw new Ledger.LedgerError("above_max", "The maximum bet here is " + s.money.format(max) + ".");
        if (s.ledger.balance(p.getUUID()) < v) throw new Ledger.LedgerError("insufficient_funds", "Not enough Indigo Credits.");
        return v;
    }

    private static String newRound() { return UUID.randomUUID().toString(); }

    private static void spin(EconomyService s, ServerPlayer p, CasinoTableEntity t, Ledger.Casino c, String amount) throws Ledger.LedgerError {
        long stake = stake(s, p, t, amount);
        String round = newRound();
        s.ledger.roundOpen(round, c.id(), p.getUUID(), stake, CasinoGames.reserveFor(Game.SLOTS, null, stake), "slots", null);
        CasinoGames.Spin r = CasinoGames.spin(RNG);
        long back = r.returned(stake);
        String reels = CasinoGames.SYMBOLS[r.reels()[0]] + " " + CasinoGames.SYMBOLS[r.reels()[1]] + " " + CasinoGames.SYMBOLS[r.reels()[2]];
        settle(s, round, back, reels + " x" + r.multiplier());
        log(p, c, "slots", stake, back, reels);
        View v = play(s, p, t, Game.SLOTS, c, back > stake ? View.WIN : back == stake ? View.INFO : View.LOSE,
            back > stake ? "You won " + s.money.format(back) + "!" : back == stake ? "Your " + s.money.plain(stake) + " came back." : "No win this time.");
        send(p, false, with(v, r.reels(), -1, s.money.plain(stake)));
        notifyOwner(s, c, p, stake, back);
    }

    private static void roulette(EconomyService s, ServerPlayer p, CasinoTableEntity t, Ledger.Casino c, String amount, int kind, int pick)
            throws Ledger.LedgerError {
        if (kind < 0 || kind >= Bet.values().length) throw new Ledger.LedgerError("bad_bet", "Pick a bet.");
        Bet bet = Bet.values()[kind];
        if (!CasinoGames.validPick(bet, pick)) throw new Ledger.LedgerError("bad_bet", bet == Bet.STRAIGHT ? "Pick a number from 0 to 36." : "Pick 1, 2 or 3.");
        long stake = stake(s, p, t, amount);
        String round = newRound();
        s.ledger.roundOpen(round, c.id(), p.getUUID(), stake, CasinoGames.reserveFor(Game.ROULETTE, bet, stake), "roulette",
            Json.obj("bet", bet.name(), "pick", pick));
        int n = CasinoGames.wheel(RNG);
        boolean won = CasinoGames.wins(bet, pick, n);
        long back = won ? stake * bet.returns : 0;
        String landed = n + (n == 0 ? " green" : CasinoGames.red(n) ? " red" : " black");
        settle(s, round, back, betName(bet, pick) + " on " + landed);
        log(p, c, "roulette", stake, back, betName(bet, pick) + " -> " + landed);
        View v = play(s, p, t, Game.ROULETTE, c, won ? View.WIN : View.LOSE,
            "The ball landed on " + landed + ". " + (won ? "You won " + s.money.format(back) + "!" : "No win on " + betName(bet, pick) + "."));
        send(p, false, with(v, new int[0], n, s.money.plain(stake)));
        notifyOwner(s, c, p, stake, back);
    }

    private static void blackjack(EconomyService s, ServerPlayer p, CasinoTableEntity t, Ledger.Casino c, Action a) throws Ledger.LedgerError {
        Hand h = HANDS.get(p.getUUID());
        if (h != null && !h.pos.equals(t.getBlockPos())) throw new Ledger.LedgerError("hand_elsewhere", "Finish your hand at the other table first.");
        switch (a.action()) {
            case Action.DEAL -> {
                if (h != null) { send(p, false, hand(s, p, t, c, h, View.INFO, "Finish this hand first.")); return; }
                long stake = stake(s, p, t, a.amount());
                String round = newRound();
                s.ledger.roundOpen(round, c.id(), p.getUUID(), stake, CasinoGames.reserveFor(Game.BLACKJACK, null, stake), "blackjack", null);
                h = new Hand(round, t.getBlockPos(), c.id(), stake, new CasinoGames.Blackjack(RNG));
                HANDS.put(p.getUUID(), h);
            }
            case Action.HIT -> { if (h == null) return; h.game.hit(); }
            case Action.STAND -> { if (h == null) return; h.game.stand(); }
            case Action.DOUBLE -> {
                if (h == null || !h.game.canDouble()) return;
                s.ledger.roundRaise(h.round, h.stake, 0);   // the reserve of 2x already covers a doubled win
                h.game.doubleDown();
            }
            default -> { return; }
        }
        if (!h.game.finished) { send(p, false, hand(s, p, t, c, h, View.NONE, "")); return; }
        HANDS.remove(p.getUUID());
        long staked = h.stake * (h.game.doubled ? 2 : 1), back = h.game.returned(h.stake);
        settle(s, h.round, back, h.game.outcome);
        log(p, c, "blackjack", staked, back, h.game.outcome);
        int kind = back > staked ? View.WIN : back == staked ? View.INFO : View.LOSE;
        String msg = switch (h.game.outcome) {
            case "blackjack" -> "Blackjack! You won " + s.money.format(back) + ".";
            case "win", "dealer bust" -> (h.game.outcome.equals("dealer bust") ? "Dealer bust. " : "You win. ") + "You won " + s.money.format(back) + ".";
            case "push" -> "Push: your " + s.money.plain(staked) + " came back.";
            case "bust" -> "Bust.";
            case "dealer blackjack" -> "Dealer has blackjack.";
            default -> "Dealer wins.";
        };
        send(p, false, hand(s, p, t, c, h, kind, msg));
        notifyOwner(s, c, p, staked, back);
    }

    private static void settle(EconomyService s, String round, long back, String outcome) {
        try {
            s.ledger.roundSettle(round, back, outcome);
        } catch (Ledger.LedgerError e) {
            // stays open in the ledger: voided on the next start (stake back); staff can see it in the log
            IndigoEconomy.LOG.error("INDIGO_CASINO_SETTLE_FAILED round={} code={}", round, e.code);
        }
    }

    private static void log(ServerPlayer p, Ledger.Casino c, String game, long stake, long back, String what) {
        IndigoEconomy.LOG.info("INDIGO_CASINO_ROUND casino={} game={} player={} stake={} returned={} result={}", c.name(), game,
            p.getGameProfile().getName(), stake, back, what);
    }

    private static void notifyOwner(EconomyService s, Ledger.Casino c, ServerPlayer p, long stake, long back) {
        ServerPlayer o = s.server.getPlayerList().getPlayer(c.owner());
        if (o == null || back <= stake * 10) return;   // owners hear about big wins, not every round
        o.displayClientMessage(Component.literal(p.getGameProfile().getName() + " won " + s.money.format(back) + " at " + c.name() + "."), false);
    }

    // ---------------------------------------------------------------- owner

    private static void ownerAction(EconomyService s, ServerPlayer p, CasinoTableEntity t, Game game, Ledger.Casino c, Action a) throws Ledger.LedgerError {
        switch (a.action()) {
            case Action.LIMITS -> {
                long min, max;
                try { min = s.money.parse(a.amount()); max = s.money.parse(a.amount2()); }
                catch (IllegalArgumentException e) { throw new Ledger.LedgerError("bad_amount", e.getMessage()); }
                long cap = s.cfg.casinoMaxBetIc * unit(s);
                if (min <= 0 || max < min) throw new Ledger.LedgerError("bad_limits", "The maximum must be at least the minimum.");
                if (max > cap) throw new Ledger.LedgerError("above_cap", "The server's highest bet is " + s.money.format(cap) + ".");
                t.limits(min, max);
                IndigoEconomy.LOG.info("INDIGO_CASINO_LIMITS casino={} pos={} min={} max={}", c.name(), t.getBlockPos(), min, max);
                send(p, false, manage(s, p, t, game, c, View.INFO, "Bets here are now " + s.money.plain(min) + " to " + s.money.plain(max) + "."));
            }
            case Action.DEPOSIT, Action.WITHDRAW -> {
                long amt;
                try { amt = s.money.parse(a.amount()); } catch (IllegalArgumentException e) { throw new Ledger.LedgerError("bad_amount", e.getMessage()); }
                boolean in = a.action() == Action.DEPOSIT;
                if (in) s.ledger.casinoDeposit(p.getUUID(), amt, null); else s.ledger.casinoWithdraw(p.getUUID(), amt, null);
                IndigoEconomy.LOG.info("INDIGO_CASINO_{} casino={} amount={}", in ? "DEPOSIT" : "WITHDRAW", c.name(), amt);
                s.push(p, false, online.indigomc.economy.net.WalletPayloads.State.NONE, "", "", 0);
                send(p, false, manage(s, p, t, game, c, View.INFO, (in ? "Added " : "Took out ") + s.money.format(amt) + (in ? " to" : " from") + " the house bank."));
            }
            default -> send(p, false, manage(s, p, t, game, c, View.NONE, ""));
        }
    }

    // ---------------------------------------------------------------- views

    static long unit(EconomyService s) { return (long) Math.pow(10, s.cfg.decimals); }

    static String title(Game g) {
        return switch (g) { case SLOTS -> "Slot machine"; case ROULETTE -> "Roulette table"; case BLACKJACK -> "Blackjack table"; };
    }

    static String betName(Bet b, int pick) {
        return switch (b) {
            case STRAIGHT -> "number " + pick;
            case RED -> "red"; case BLACK -> "black"; case ODD -> "odd"; case EVEN -> "even";
            case LOW -> "1-18"; case HIGH -> "19-36";
            case DOZEN -> pick == 1 ? "1st dozen" : pick == 2 ? "2nd dozen" : "3rd dozen";
            case COLUMN -> "column " + pick;
        };
    }

    /** What the house bank can currently cover, as the largest bet it would accept for the riskiest bet on this game. */
    private static String cover(EconomyService s, CasinoTableEntity t, Game game, Ledger.Casino c) {
        long bank = s.ledger.casinoBank(c.id());
        long cap = Math.min(t.maxBet, s.cfg.casinoMaxBetIc * unit(s));
        return switch (game) {
            case SLOTS -> coverText(s, t, Math.min(cap, bank / (CasinoGames.SLOT_MAX_MULTIPLIER - 1)));
            case BLACKJACK -> coverText(s, t, Math.min(cap, bank / 2));
            case ROULETTE -> {
                long straight = Math.min(cap, bank / 35), even = Math.min(cap, bank);
                yield even < t.minBet ? "The house can't take bets right now." : straight < t.minBet
                    ? "House covers up to " + s.money.plain(even) + " (numbers closed)."
                    : "House covers up to " + s.money.plain(straight) + " on a number, " + s.money.plain(even) + " on the rest.";
            }
        };
    }

    private static String coverText(EconomyService s, CasinoTableEntity t, long max) {
        return max < t.minBet ? "The house can't take bets right now." : "House covers bets up to " + s.money.plain(max) + ".";
    }

    private static View base(EconomyService s, ServerPlayer p, CasinoTableEntity t, Game game, Ledger.Casino c, int mode, int kind, String msg) {
        long max = Math.min(t.maxBet, s.cfg.casinoMaxBetIc * unit(s));
        return new View(true, t.getBlockPos(), mode, c.name(), ownerName(s, c), s.money.plain(t.minBet), s.money.plain(max), cover(s, t, game, c),
            s.money.format(s.ledger.balance(p.getUUID())), kind, msg == null ? "" : msg, new int[0], -1, new int[0], new int[0], false, false,
            false, 0, 0, s.money.plain(t.minBet), "", "", 0, "", "", "", title(game));
    }

    private static View play(EconomyService s, ServerPlayer p, CasinoTableEntity t, Game game, Ledger.Casino c, int kind, String msg) {
        Hand h = HANDS.get(p.getUUID());
        if (game == Game.BLACKJACK && h != null && h.pos.equals(t.getBlockPos())) return hand(s, p, t, c, h, kind, msg);
        int mode = switch (game) { case SLOTS -> View.SLOTS; case ROULETTE -> View.ROULETTE; case BLACKJACK -> View.BLACKJACK; };
        return base(s, p, t, game, c, mode, kind, msg);
    }

    private static View hand(EconomyService s, ServerPlayer p, CasinoTableEntity t, Ledger.Casino c, Hand h, int kind, String msg) {
        View b = base(s, p, t, Game.BLACKJACK, c, View.BLACKJACK, kind, msg);
        CasinoGames.Blackjack g = h.game;
        boolean hidden = !g.dealerRevealed;
        int[] pc = cards(g.player), dc = cards(hidden ? g.dealer.subList(0, 1) : g.dealer);
        int dealerTotal = CasinoGames.Blackjack.value(hidden ? g.dealer.subList(0, 1) : g.dealer);
        return new View(b.open(), b.pos(), b.mode(), b.casino(), b.owner(), b.minBet(), b.maxBet(), b.coverNote(), b.balance(), kind, msg,
            new int[0], -1, pc, dc, hidden, !g.finished, g.canDouble(), CasinoGames.Blackjack.value(g.player), dealerTotal,
            s.money.plain(h.stake * (g.doubled ? 2 : 1)), "", "", 0, "", "", "", b.game());
    }

    private static int[] cards(List<int[]> cs) {
        int[] out = new int[cs.size()];
        for (int i = 0; i < out.length; i++) out[i] = cs.get(i)[0] * 4 + cs.get(i)[1];
        return out;
    }

    private static View with(View v, int[] reels, int wheel, String stake) {
        return new View(v.open(), v.pos(), v.mode(), v.casino(), v.owner(), v.minBet(), v.maxBet(), v.coverNote(), v.balance(), v.resultKind(),
            v.message(), reels, wheel, v.playerCards(), v.dealerCards(), v.dealerHidden(), v.inHand(), v.canDouble(), v.playerTotal(),
            v.dealerTotal(), stake, v.bank(), v.inPlay(), v.rounds(), v.staked(), v.returned(), v.houseResult(), v.game());
    }

    private static View manage(EconomyService s, ServerPlayer p, CasinoTableEntity t, Game game, Ledger.Casino c, int kind, String msg) {
        View b = base(s, p, t, game, c, View.MANAGE, kind, msg);
        Ledger.Casino fresh = s.ledger.casino(c.id()).orElse(c);
        long inPlay = 0;
        for (JsonObject r : s.ledger.openRounds())
            if (r.get("casino").getAsString().equals(c.id())) inPlay += r.get("stake").getAsLong() + r.get("reserve").getAsLong();
        long house = fresh.staked() - fresh.returned();
        return new View(b.open(), b.pos(), b.mode(), b.casino(), b.owner(), s.money.plain(t.minBet), s.money.plain(t.maxBet), b.coverNote(),
            b.balance(), kind, msg, new int[0], -1, new int[0], new int[0], false, false, false, 0, 0, "",
            s.money.format(s.ledger.casinoBank(c.id())), s.money.format(inPlay), fresh.rounds(), s.money.format(fresh.staked()),
            s.money.format(fresh.returned()), s.money.signed(house), b.game());
    }

    private static View closeView(BlockPos pos) {
        return new View(false, pos, View.CLOSE, "", "", "", "", "", "", View.INFO, "This casino has closed.", new int[0], -1, new int[0],
            new int[0], false, false, false, 0, 0, "", "", "", 0, "", "", "", "");
    }

    private static String ownerName(EconomyService s, Ledger.Casino c) {
        return s.ledger.account(c.owner()).map(Ledger.Account::name).orElse("someone");
    }

    private static void send(ServerPlayer p, boolean open, View v) {
        View out = open == v.open() ? v : new View(open, v.pos(), v.mode(), v.casino(), v.owner(), v.minBet(), v.maxBet(), v.coverNote(),
            v.balance(), v.resultKind(), v.message(), v.reels(), v.wheel(), v.playerCards(), v.dealerCards(), v.dealerHidden(), v.inHand(),
            v.canDouble(), v.playerTotal(), v.dealerTotal(), v.stake(), v.bank(), v.inPlay(), v.rounds(), v.staked(), v.returned(),
            v.houseResult(), v.game());
        EconomyService s = EconomyService.get();
        if (s != null && s.hasUi(p)) PacketDistributor.sendToPlayer(p, out);
        LAST.put(p.getUUID(), out);
    }

    /** The last view sent to each player (the lab probe reads it; fake players have no client). */
    public static final Map<UUID, View> LAST = new HashMap<>();
}
