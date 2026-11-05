package online.indigomc.economy.core;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The casino games' rules (0.5.0, decision D61): fixed, fair and the same on every server table, so owners can't rig
 * them. Amounts are whole minor units (cents); every result is the player's TOTAL RETURN (stake included), never more
 * than {@link #maxReturn} for its game, which is what the house bank reserves before a round starts.
 *
 * <ul>
 *   <li>Slots: three identical 32-stop reels, paytable below. Exact payback 95.001% (every combination enumerated in
 *       CoreTest), 40.5% of spins return something, top prize three sevens at 100x.</li>
 *   <li>Roulette: European single zero. Straight 35:1, dozen and column 2:1, red/black, odd/even, low/high 1:1.
 *       House edge 1/37 (2.7%) on every bet.</li>
 *   <li>Blackjack: infinite deck, dealer stands on soft 17, blackjack pays 3:2, double on the first two cards,
 *       no split, no insurance. House edge about 0.7%.</li>
 * </ul>
 */
public final class CasinoGames {
    private CasinoGames() {}

    public enum Game { SLOTS, ROULETTE, BLACKJACK }

    // ---------------------------------------------------------------- slots

    public static final String[] SYMBOLS = {"cherry", "lemon", "plum", "bell", "bar", "seven"};
    /** Stops per symbol on each reel (32 in all). */
    public static final int[] WEIGHTS = {10, 8, 6, 4, 3, 1};
    /** Total return for three of a kind, in the order of {@link #SYMBOLS}. */
    public static final int[] THREE = {5, 5, 10, 30, 50, 100};
    public static final int TWO_CHERRIES = 2, FIRST_CHERRY = 1;
    public static final int SLOT_MAX_MULTIPLIER = 100;

    public record Spin(int[] reels, int multiplier) {
        public long returned(long stake) { return stake * multiplier; }
    }

    public static int reel(RandomGenerator rng) {
        int r = rng.nextInt(32);
        for (int i = 0; i < WEIGHTS.length; i++) {
            if (r < WEIGHTS[i]) return i;
            r -= WEIGHTS[i];
        }
        throw new IllegalStateException();
    }

    public static int slotMultiplier(int a, int b, int c) {
        if (a == b && b == c) return THREE[a];
        int cherries = (a == 0 ? 1 : 0) + (b == 0 ? 1 : 0) + (c == 0 ? 1 : 0);
        if (cherries == 2) return TWO_CHERRIES;
        if (cherries == 1 && a == 0) return FIRST_CHERRY;
        return 0;
    }

    public static Spin spin(RandomGenerator rng) {
        int a = reel(rng), b = reel(rng), c = reel(rng);
        return new Spin(new int[]{a, b, c}, slotMultiplier(a, b, c));
    }

    // ---------------------------------------------------------------- roulette

    /** Bet kinds. For STRAIGHT the pick is the number 0-36; DOZEN and COLUMN pick 1-3; the rest ignore it. */
    public enum Bet {
        STRAIGHT(36), RED(2), BLACK(2), ODD(2), EVEN(2), LOW(2), HIGH(2), DOZEN(3), COLUMN(3);
        /** Total return on a win, as a multiple of the stake. */
        public final int returns;
        Bet(int returns) { this.returns = returns; }
    }

    private static final int[] REDS = {1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36};

    public static boolean red(int n) {
        for (int r : REDS) if (r == n) return true;
        return false;
    }

    public static boolean validPick(Bet bet, int pick) {
        return switch (bet) {
            case STRAIGHT -> pick >= 0 && pick <= 36;
            case DOZEN, COLUMN -> pick >= 1 && pick <= 3;
            default -> true;
        };
    }

    public static boolean wins(Bet bet, int pick, int n) {
        if (n == 0) return bet == Bet.STRAIGHT && pick == 0;
        return switch (bet) {
            case STRAIGHT -> pick == n;
            case RED -> red(n);
            case BLACK -> !red(n);
            case ODD -> n % 2 == 1;
            case EVEN -> n % 2 == 0;
            case LOW -> n <= 18;
            case HIGH -> n >= 19;
            case DOZEN -> (n - 1) / 12 + 1 == pick;
            case COLUMN -> (n - 1) % 3 + 1 == pick;
        };
    }

    public static int wheel(RandomGenerator rng) { return rng.nextInt(37); }

    // ---------------------------------------------------------------- blackjack

    /** A hand in progress. Cards are 1-13 (ace to king); suits are cosmetic (0-3). */
    public static final class Blackjack {
        public final List<int[]> player = new ArrayList<>(), dealer = new ArrayList<>();
        public boolean doubled, finished, dealerRevealed;
        public String outcome = "";
        /** Total return as a multiple of the ORIGINAL stake, in halves (5 = 2.5x for a blackjack). */
        public int returnHalves;
        private final RandomGenerator rng;

        public Blackjack(RandomGenerator rng) {
            this.rng = rng;
            player.add(draw());
            dealer.add(draw());
            player.add(draw());
            dealer.add(draw());
            if (value(player) == 21 || value(dealer) == 21) finish();   // naturals settle at once
        }

        private int[] draw() { return new int[]{rng.nextInt(13) + 1, rng.nextInt(4)}; }

        public boolean canDouble() { return !finished && player.size() == 2 && !doubled; }

        public void hit() {
            if (finished) return;
            player.add(draw());
            if (value(player) >= 21) finish();
        }

        public void stand() { if (!finished) finish(); }

        public void doubleDown() {
            if (!canDouble()) return;
            doubled = true;
            player.add(draw());
            finish();
        }

        private void finish() {
            finished = true;
            dealerRevealed = true;
            int p = value(player);
            boolean pNatural = player.size() == 2 && p == 21 && !doubled;
            boolean dNatural = dealer.size() == 2 && value(dealer) == 21;
            int unit = doubled ? 2 : 1;   // stakes in play, in original-stake units
            if (pNatural || dNatural) {
                if (pNatural && dNatural) { outcome = "push"; returnHalves = 2; }
                else if (pNatural) { outcome = "blackjack"; returnHalves = 5; }
                else { outcome = "dealer blackjack"; returnHalves = 0; }
                return;
            }
            if (p > 21) { outcome = "bust"; returnHalves = 0; return; }
            while (value(dealer) < 17) dealer.add(draw());   // stands on all 17s, soft included
            int d = value(dealer);
            if (d > 21 || p > d) { outcome = d > 21 ? "dealer bust" : "win"; returnHalves = 4 * unit; }
            else if (p == d) { outcome = "push"; returnHalves = 2 * unit; }
            else { outcome = "lose"; returnHalves = 0; }
        }

        public long returned(long stake) { return stake * returnHalves / 2; }

        public static int value(List<int[]> cards) {
            int total = 0, aces = 0;
            for (int[] c : cards) {
                int v = Math.min(c[0], 10);
                if (c[0] == 1) { aces++; v = 11; }
                total += v;
            }
            while (total > 21 && aces-- > 0) total -= 10;
            return total;
        }
    }

    /**
     * What the house must reserve beyond the player's stake so the best possible outcome is always covered:
     * slots 99x, roulette 35x (straight) or less, blackjack 2x (a doubled win returns 4x the original stake, of which
     * the player put in 2x).
     */
    public static long reserveFor(Game game, Bet bet, long stake) {
        return switch (game) {
            case SLOTS -> stake * (SLOT_MAX_MULTIPLIER - 1);
            case ROULETTE -> stake * (bet.returns - 1);
            case BLACKJACK -> stake * 2;
        };
    }
}
