package online.indigomc.economy.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import online.indigomc.economy.net.CasinoPayloads.Action;
import online.indigomc.economy.net.CasinoPayloads.View;

import java.util.Random;

/**
 * The casino screen for one table: the first-time notice, slots, roulette, blackjack, or the owner's management page.
 * Every number comes from the server; the reel and wheel animations only play back a result the server already decided.
 */
public final class CasinoScreen extends Screen {
    private static final int W = 320, H = 236;
    private static final int BG = 0xF0141428, BORDER = 0xFF5B4FE0, HEAD_A = 0xFF3B2F99, HEAD_B = 0xFF5B4FE0, FELT = 0xFF1E5A3A, FELT_EDGE = 0xFF0F3D26;
    private static final int TEXT = 0xFFFFFFFF, MUTED = 0xFFA6A3C8, GREEN = 0xFF5BE39A, RED = 0xFFFF7A7A, GOLD = 0xFFFFD36B, AQUA = 0xFF8FD8FF;
    private static final String[] SYM = {"CHERRY", "LEMON", "PLUM", "BELL", "BAR", "7"};
    private static final int[] SYM_COLOR = {0xFFE0413A, 0xFFF4E04D, 0xFFB05BE0, 0xFFFFC23D, 0xFFFFFFFF, 0xFFFF3B3B};
    private static final String[] RANK = {"", "A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
    private static final String[] SUIT = {"♠", "♥", "♦", "♣"};
    private static final String[] BETS = {"Number", "Red", "Black", "Odd", "Even", "1-18", "19-36", "Dozen", "Column"};

    final BlockPos pos;
    private View v;
    private int left, top;
    private String amount = "", amount2 = "", pick = "17";
    private int bet = 1, groupPick = 1;
    private int anim = 0;   // ticks of reel/wheel animation left
    private final Random fake = new Random();

    CasinoScreen(View v) {
        super(Component.literal("Casino"));
        this.pos = v.pos();
        this.v = v;
        amount = v.mode() == View.MANAGE ? "" : v.stake();
        if (v.mode() == View.MANAGE) { amount = v.minBet(); amount2 = v.maxBet(); }
    }

    void onView(View next) {
        boolean relayout = next.mode() != v.mode() || next.inHand() != v.inHand() || next.canDouble() != v.canDouble();
        boolean newResult = (next.reels().length == 3 || next.wheel() >= 0) && next.resultKind() != View.ERROR;
        if (next.mode() == View.MANAGE && v.mode() != View.MANAGE) { amount = next.minBet(); amount2 = next.maxBet(); }
        v = next;
        if (newResult) anim = next.mode() == View.ROULETTE ? 40 : 28;
        if (relayout) rebuildWidgets();
    }

    @Override public boolean isPauseScreen() { return false; }

    private void send(int action, String a, String b, int x, int y) {
        PacketDistributor.sendToServer(new Action(pos, action, a, b, x, y));
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        int by = top + H - 30;
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(left + W - 58, top + 5, 50, 14).build());
        switch (v.mode()) {
            case View.ACK -> {
                addRenderableWidget(Button.builder(Component.literal("I understand, let me play"), b -> send(Action.ACK, "", "", 0, 0))
                    .bounds(left + 20, by, 170, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Not now"), b -> onClose()).bounds(left + 200, by, 100, 20).build());
            }
            case View.SLOTS -> {
                betBox(left + 60, by);
                addRenderableWidget(Button.builder(Component.literal("Spin"), b -> { if (anim == 0) send(Action.SPIN, amount, "", 0, 0); })
                    .bounds(left + 212, by - 2, 90, 20).build());
            }
            case View.ROULETTE -> {
                int x = left + 12, y = top + 146;
                for (int i = 0; i < BETS.length; i++) {
                    final int k = i;
                    Button b = Button.builder(Component.literal(BETS[i]), btn -> { bet = k; rebuildWidgets(); })
                        .bounds(x + (i % 5) * 60, y + (i / 5) * 18, 58, 16).build();
                    b.active = bet != i;
                    addRenderableWidget(b);
                }
                if (bet == 0) {
                    EditBox n = new EditBox(font, x + 250, y + 18, 50, 16, Component.literal("Number"));
                    n.setMaxLength(2);
                    n.setFilter(s -> s.matches("[0-9]*"));
                    n.setValue(pick);
                    n.setResponder(s -> pick = s);
                    addRenderableWidget(n);
                } else if (bet == 7 || bet == 8) {
                    for (int g = 1; g <= 3; g++) {
                        final int k = g;
                        Button b = Button.builder(Component.literal(String.valueOf(g)), btn -> { groupPick = k; rebuildWidgets(); })
                            .bounds(x + 240 + (g - 1) * 22, y + 18, 20, 16).build();
                        b.active = groupPick != g;
                        addRenderableWidget(b);
                    }
                }
                betBox(left + 60, by);
                addRenderableWidget(Button.builder(Component.literal("Spin"), b -> {
                    if (anim > 0) return;
                    int kind = switch (bet) { case 0 -> 0; case 1 -> 1; case 2 -> 2; case 3 -> 3; case 4 -> 4; case 5 -> 5; case 6 -> 6; case 7 -> 7; default -> 8; };
                    int p = bet == 0 ? parse(pick) : bet >= 7 ? groupPick : 0;
                    send(Action.ROULETTE, amount, "", kind, p);
                }).bounds(left + 212, by - 2, 90, 20).build());
            }
            case View.BLACKJACK -> {
                if (v.inHand()) {
                    addRenderableWidget(Button.builder(Component.literal("Hit"), b -> send(Action.HIT, "", "", 0, 0)).bounds(left + 20, by - 2, 80, 20).build());
                    addRenderableWidget(Button.builder(Component.literal("Stand"), b -> send(Action.STAND, "", "", 0, 0)).bounds(left + 110, by - 2, 80, 20).build());
                    Button d = Button.builder(Component.literal("Double"), b -> send(Action.DOUBLE, "", "", 0, 0)).bounds(left + 200, by - 2, 80, 20).build();
                    d.active = v.canDouble();
                    addRenderableWidget(d);
                } else {
                    betBox(left + 60, by);
                    addRenderableWidget(Button.builder(Component.literal("Deal"), b -> send(Action.DEAL, amount, "", 0, 0))
                        .bounds(left + 212, by - 2, 90, 20).build());
                }
            }
            case View.MANAGE -> {
                int y = top + 150;
                EditBox min = money(left + 96, y, amount, s -> amount = s);
                EditBox max = money(left + 174, y, amount2, s -> amount2 = s);
                min.setWidth(58);
                max.setWidth(58);
                addRenderableWidget(min);
                addRenderableWidget(max);
                addRenderableWidget(Button.builder(Component.literal("Save limits"), b -> send(Action.LIMITS, amount, amount2, 0, 0))
                    .bounds(left + 238, y - 1, 72, 18).build());
                EditBox bank = money(left + 96, y + 34, "", s -> bankAmount = s);
                addRenderableWidget(bank);
                addRenderableWidget(Button.builder(Component.literal("Deposit"), b -> send(Action.DEPOSIT, bankAmount, "", 0, 0))
                    .bounds(left + 170, y + 33, 64, 18).build());
                addRenderableWidget(Button.builder(Component.literal("Withdraw"), b -> send(Action.WITHDRAW, bankAmount, "", 0, 0))
                    .bounds(left + 238, y + 33, 72, 18).build());
            }
            default -> { }
        }
    }

    private String bankAmount = "";

    private EditBox money(int x, int y, String value, java.util.function.Consumer<String> r) {
        EditBox e = new EditBox(font, x, y, 64, 16, Component.literal("amount"));
        e.setMaxLength(12);
        e.setFilter(s -> s.matches("[0-9.,]*"));
        e.setValue(value);
        e.setResponder(r);
        e.setHint(Component.literal("0.00").withColor(0xFF6E6A99));
        return e;
    }

    private void betBox(int x, int y) {
        EditBox e = money(x, y, amount, s -> amount = s);
        addRenderableWidget(e);
        addRenderableWidget(Button.builder(Component.literal("Min"), b -> { amount = v.minBet(); rebuildWidgets(); }).bounds(x + 68, y - 1, 34, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Max"), b -> { amount = v.maxBet(); rebuildWidgets(); }).bounds(x + 104, y - 1, 34, 18).build());
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    @Override
    public void tick() {
        super.tick();
        if (anim > 0) anim--;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        super.renderBackground(g, mx, my, pt);
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, BORDER);
        g.fill(left, top, left + W, top + H, BG);
        g.fillGradient(left, top, left + W, top + 24, HEAD_A, HEAD_B);
        if (v.mode() == View.ROULETTE || v.mode() == View.BLACKJACK || v.mode() == View.SLOTS) {
            g.fill(left + 8, top + 30, left + W - 8, top + 140, FELT_EDGE);
            g.fill(left + 10, top + 32, left + W - 10, top + 138, FELT);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        g.drawString(font, v.casino() + " · " + v.game(), left + 10, top + 8, TEXT);
        switch (v.mode()) {
            case View.ACK -> renderAck(g);
            case View.SLOTS -> renderSlots(g);
            case View.ROULETTE -> renderRoulette(g);
            case View.BLACKJACK -> renderBlackjack(g);
            case View.MANAGE -> renderManage(g);
            default -> { }
        }
        if (v.mode() == View.SLOTS || v.mode() == View.ROULETTE || v.mode() == View.BLACKJACK) {
            int y = top + H - 30;
            if (!(v.mode() == View.BLACKJACK && v.inHand())) g.drawString(font, "Bet", left + 12, y + 4, MUTED, false);
            String limits = "Bets " + v.minBet() + " to " + v.maxBet() + " IC";
            g.drawString(font, limits, left + 12, top + H - 50, MUTED, false);
            g.drawString(font, font.plainSubstrByWidth(v.coverNote(), W - 24), left + 12, top + H - 40, 0xFF7E7AA8, false);
            String bal = "Wallet " + v.balance();
            g.drawString(font, bal, left + W - 12 - font.width(bal), top + H - 50, AQUA, false);
        }
        if (anim == 0 && !v.message().isEmpty()) {
            int c = switch (v.resultKind()) { case View.WIN -> GREEN; case View.LOSE -> RED; case View.ERROR -> RED; case View.INFO -> GOLD; default -> MUTED; };
            int y = v.mode() == View.MANAGE ? top + 214 : v.mode() == View.ROULETTE ? top + 124 : top + 150;
            g.drawCenteredString(font, font.plainSubstrByWidth(v.message(), W - 24), left + W / 2, y, c);
        }
    }

    private void renderAck(GuiGraphics g) {
        int x = left + 16, y = top + 38;
        String[] lines = {
            "Before you play at " + v.casino() + ":",
            "",
            "• This is play money. Indigo Credits are never sold",
            "  for real money and can never be cashed out.",
            "• Casino games are meant for grown-ups.",
            "• The house keeps a small edge on every game, so",
            "  over time the house wins. Only bet what you're",
            "  happy to lose.",
            "• " + v.owner() + " runs this casino and pays winnings",
            "  from their own house bank.",
        };
        for (int i = 0; i < lines.length; i++) g.drawString(font, lines[i], x, y + i * 12, i == 0 ? GOLD : TEXT, false);
    }

    private void renderSlots(GuiGraphics g) {
        int[] r = v.reels();
        int cx = left + W / 2, y = top + 46;
        for (int i = 0; i < 3; i++) {
            int x = cx - 105 + i * 72;
            g.fill(x, y, x + 66, y + 56, 0xFF0B0B18);
            g.fill(x + 2, y + 2, x + 64, y + 54, 0xFF26244A);
            int sym;
            boolean stopped = anim <= 28 - (10 + i * 7);
            if (r.length == 3 && stopped) sym = r[i];
            else if (anim > 0) sym = fake.nextInt(6);
            else sym = -1;
            if (sym >= 0) {
                g.pose().pushPose();
                float scale = sym == 5 ? 3f : 1.5f;
                g.pose().translate(x + 33, y + 28 - 4 * scale, 0);
                g.pose().scale(scale, scale, 1);
                g.drawCenteredString(font, SYM[sym], 0, 0, SYM_COLOR[sym]);
                g.pose().popPose();
            }
        }
        g.drawCenteredString(font, "Three 7s pay 100x · BARs 50x · Bells 30x · Plums 10x", cx, top + 106, 0xFFCFE8D8);
        g.drawCenteredString(font, "Lemons or Cherries 5x · two Cherries 2x · Cherry first 1x", cx, top + 116, 0xFFCFE8D8);
    }

    private void renderRoulette(GuiGraphics g) {
        int cx = left + W / 2, y = top + 40;
        int n = v.wheel();
        if (anim > 0) n = fake.nextInt(37);
        if (n >= 0) {
            int color = n == 0 ? 0xFF1F8A4C : red(n) ? 0xFFC0282D : 0xFF1A1A1A;
            g.fill(cx - 30, y, cx + 30, y + 44, 0xFFE8D9A8);
            g.fill(cx - 27, y + 3, cx + 27, y + 41, color);
            g.pose().pushPose();
            g.pose().translate(cx, y + 14, 0);
            g.pose().scale(2.5f, 2.5f, 1);
            g.drawCenteredString(font, String.valueOf(n), 0, 0, TEXT);
            g.pose().popPose();
        } else {
            g.drawCenteredString(font, "Place a bet and spin", cx, y + 18, 0xFFCFE8D8);
        }
        String sel = switch (bet) {
            case 0 -> "Your bet: number " + (pick.isEmpty() ? "?" : pick) + " (pays 35 to 1)";
            case 7 -> "Your bet: " + (groupPick == 1 ? "1st" : groupPick == 2 ? "2nd" : "3rd") + " dozen (pays 2 to 1)";
            case 8 -> "Your bet: column " + groupPick + " (pays 2 to 1)";
            default -> "Your bet: " + BETS[bet].toLowerCase() + " (pays 1 to 1)";
        };
        g.drawCenteredString(font, sel, cx, top + 96, GOLD);
        g.drawCenteredString(font, "European wheel, one zero", cx, top + 108, 0xFFCFE8D8);
    }

    private static boolean red(int n) {
        for (int r : new int[]{1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36}) if (r == n) return true;
        return false;
    }

    private void renderBlackjack(GuiGraphics g) {
        int x = left + 20;
        g.drawString(font, "Dealer" + (v.dealerCards().length > 0 ? "  " + v.dealerTotal() + (v.dealerHidden() ? "+" : "") : ""), x, top + 36, 0xFFCFE8D8, false);
        int i = 0;
        for (int c : v.dealerCards()) card(g, x + i++ * 28, top + 48, c);
        if (v.dealerHidden()) back(g, x + i * 28, top + 48);
        g.drawString(font, "You" + (v.playerCards().length > 0 ? "  " + v.playerTotal() : ""), x, top + 84, 0xFFCFE8D8, false);
        i = 0;
        for (int c : v.playerCards()) card(g, x + i++ * 28, top + 96 - 4, c);
        if (v.playerCards().length > 0) {
            String st = "Stake " + v.stake() + " IC";
            g.drawString(font, st, left + W - 20 - font.width(st), top + 84, GOLD, false);
        } else {
            g.drawCenteredString(font, "Blackjack pays 3 to 2 · dealer stands on 17", left + W / 2, top + 70, 0xFFCFE8D8);
        }
    }

    private void card(GuiGraphics g, int x, int y, int c) {
        int rank = c / 4, suit = c % 4;
        g.fill(x, y, x + 24, y + 32, 0xFF303030);
        g.fill(x + 1, y + 1, x + 23, y + 31, 0xFFF7F4EA);
        int col = suit == 1 || suit == 2 ? 0xFFC0282D : 0xFF151515;
        g.drawString(font, RANK[Math.max(1, Math.min(13, rank))], x + 3, y + 3, col, false);
        g.drawString(font, SUIT[suit], x + 14, y + 21, col, false);
    }

    private void back(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 24, y + 32, 0xFF303030);
        g.fill(x + 1, y + 1, x + 23, y + 31, HEAD_A);
        g.fill(x + 4, y + 4, x + 20, y + 28, HEAD_B);
    }

    private void renderManage(GuiGraphics g) {
        int x = left + 14, y = top + 32;
        g.drawString(font, "Your casino", x, y, GOLD, false);
        String[][] rows = {
            {"House bank", v.bank()}, {"In play now", v.inPlay()}, {"Rounds played", String.valueOf(v.rounds())},
            {"Bets taken", v.staked()}, {"Paid back to players", v.returned()}, {"House result", v.houseResult()},
        };
        for (int i = 0; i < rows.length; i++) {
            g.drawString(font, rows[i][0], x, y + 14 + i * 12, MUTED, false);
            g.drawString(font, rows[i][1], left + W - 14 - font.width(rows[i][1]), y + 14 + i * 12,
                i == 5 ? (v.houseResult().startsWith("-") ? RED : GREEN) : TEXT, false);
        }
        g.drawString(font, "This " + v.game().toLowerCase(), x, top + 138, GOLD, false);
        g.drawString(font, "Bets from", x, top + 154, MUTED, false);
        g.drawString(font, "to", left + 160, top + 154, MUTED, false);
        g.drawString(font, "House bank", x, top + 188, MUTED, false);
        g.drawString(font, font.plainSubstrByWidth(v.coverNote(), W - 28), x, top + 203, 0xFF7E7AA8, false);
    }
}
