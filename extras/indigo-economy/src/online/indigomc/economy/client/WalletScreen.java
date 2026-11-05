package online.indigomc.economy.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import online.indigomc.economy.net.WalletPayloads;
import online.indigomc.economy.net.WalletPayloads.Row;
import online.indigomc.economy.net.WalletPayloads.State;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.UUID;

/**
 * The Indigo wallet: balance, recent activity, send money (with a review step) and Discord linking.
 * Everything shown comes from the server; the screen only sends intents.
 */
public final class WalletScreen extends Screen {
    private static final int W = 320, H = 226;
    private static final int BG = 0xF0141428, PANEL = 0xFF1C1B3A, BORDER = 0xFF5B4FE0, HEAD_A = 0xFF3B2F99, HEAD_B = 0xFF5B4FE0;
    private static final int TEXT = 0xFFFFFFFF, MUTED = 0xFFA6A3C8, GREEN = 0xFF5BE39A, RED = 0xFFFF7A7A, GOLD = 0xFFFFD36B, AQUA = 0xFF8FD8FF;

    enum Tab { ACTIVITY, SEND, DISCORD }
    enum Step { FORM, REVIEW, SENDING }

    private Tab tab = Tab.ACTIVITY;
    private Step step = Step.FORM;
    private boolean confirmUnlink = false;
    private String to = "", amount = "", note = "", requestId = null;
    private EditBox toBox, amountBox, noteBox;
    private int left, top;
    private String message = "";
    private int messageColor = MUTED;

    public WalletScreen() {
        super(Component.literal("Indigo Wallet"));
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        int tx = left + 10, ty = top + 72;
        addRenderableWidget(tabButton("Activity", Tab.ACTIVITY, tx, ty));
        addRenderableWidget(tabButton("Send", Tab.SEND, tx + 66, ty));
        addRenderableWidget(tabButton("Discord", Tab.DISCORD, tx + 132, ty));
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(left + W - 58, ty, 48, 16).build());
        int cx = left + 16, cy = top + 98;
        if (tab == Tab.SEND) {
            if (step == Step.FORM) {
                toBox = box(cx + 58, cy, 140, "To", 16, "player name");
                toBox.setValue(to);
                toBox.setResponder(s -> { to = s; suggest(); });
                amountBox = box(cx + 58, cy + 22, 90, "Amount", 20, "0.00");
                amountBox.setFilter(s -> s.matches("[0-9.,]*"));
                amountBox.setValue(amount);
                amountBox.setResponder(s -> amount = s);
                noteBox = box(cx + 58, cy + 44, 220, "Note", 60, "optional");
                noteBox.setValue(note);
                noteBox.setResponder(s -> note = s);
                addRenderableWidget(Button.builder(Component.literal("Review"), b -> review()).bounds(cx + 58, cy + 70, 80, 18).build());
                setInitialFocus(to.isEmpty() ? toBox : amountBox);
                suggest();
            } else if (step == Step.REVIEW) {
                addRenderableWidget(Button.builder(Component.literal("Confirm"), b -> confirm()).bounds(cx + 58, cy + 70, 80, 18).build());
                addRenderableWidget(Button.builder(Component.literal("Back"), b -> { step = Step.FORM; rebuildWidgets(); })
                    .bounds(cx + 146, cy + 70, 60, 18).build());
            }
        } else if (tab == Tab.DISCORD) {
            State s = WalletClient.last;
            boolean linked = s != null && s.linked();
            if (linked && !confirmUnlink) {
                addRenderableWidget(Button.builder(Component.literal("Unlink..."), b -> { confirmUnlink = true; rebuildWidgets(); })
                    .bounds(cx, cy + 70, 80, 18).build());
            } else if (linked) {
                addRenderableWidget(Button.builder(Component.literal("Yes, unlink"), b -> {
                    confirmUnlink = false;
                    WalletClient.request(WalletPayloads.Request.UNLINK);
                    rebuildWidgets();
                }).bounds(cx, cy + 70, 80, 18).build());
                addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> { confirmUnlink = false; rebuildWidgets(); })
                    .bounds(cx + 88, cy + 70, 60, 18).build());
            } else {
                boolean live = liveCode();
                addRenderableWidget(Button.builder(Component.literal(live ? "New code" : "Get link code"),
                    b -> WalletClient.request(WalletPayloads.Request.LINK_CODE)).bounds(cx, cy + 88, 90, 18).build());
                if (live)
                    addRenderableWidget(Button.builder(Component.literal("Copy"), b -> {
                        minecraft.keyboardHandler.setClipboard(WalletClient.linkCode);
                        setMessage("Code copied.", AQUA);
                    }).bounds(cx + 96, cy + 88, 50, 18).build());
            }
        }
    }

    private Button tabButton(String label, Tab t, int x, int y) {
        Button b = Button.builder(Component.literal(label), btn -> {
            tab = t;
            step = Step.FORM;
            confirmUnlink = false;
            rebuildWidgets();
        }).bounds(x, y, 62, 16).build();
        b.active = tab != t;
        return b;
    }

    private EditBox box(int x, int y, int w, String name, int max, String hint) {
        EditBox e = new EditBox(font, x, y, w, 16, Component.literal(name));
        e.setMaxLength(max);
        e.setHint(Component.literal(hint).withColor(0xFF6E6A99));
        addRenderableWidget(e);
        return e;
    }

    private void suggest() {
        if (toBox == null) return;
        State s = WalletClient.last;
        String hit = "";
        if (s != null && !to.isEmpty())
            for (String n : s.recipients())
                if (n.toLowerCase(Locale.ROOT).startsWith(to.toLowerCase(Locale.ROOT)) && n.length() > to.length()) { hit = n.substring(to.length()); break; }
        toBox.setSuggestion(hit);
    }

    private void review() {
        if (to.isBlank() || amount.isBlank()) { setMessage("Enter who to pay and how much.", RED); return; }
        requestId = UUID.randomUUID().toString();   // fixed for this review: confirming twice sends once
        step = Step.REVIEW;
        setMessage("", MUTED);
        rebuildWidgets();
    }

    private void confirm() {
        if (step != Step.REVIEW) return;
        step = Step.SENDING;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new WalletPayloads.Transfer(to.trim(), amount.trim(), note.trim(), requestId));
        setMessage("Sending...", MUTED);
        rebuildWidgets();
    }

    void onState(State s) {
        if (s.resultKind() != State.NONE) setMessage(s.result(), s.resultKind() == State.OK ? GREEN : s.resultKind() == State.ERROR ? RED : AQUA);
        if (step == Step.SENDING) {
            if (s.resultKind() == State.OK) { to = ""; amount = ""; note = ""; step = Step.FORM; }
            else if (s.resultKind() == State.ERROR) step = Step.REVIEW;
        }
        rebuildWidgets();
    }

    private void setMessage(String m, int color) {
        message = m;
        messageColor = color;
    }

    private boolean liveCode() {
        return !WalletClient.linkCode.isEmpty() && System.currentTimeMillis() < WalletClient.linkExpiresAt;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (toBox != null && toBox.isFocused() && key == GLFW.GLFW_KEY_TAB && tab == Tab.SEND && step == Step.FORM) {
            State s = WalletClient.last;
            if (s != null)
                for (String n : s.recipients())
                    if (!to.isEmpty() && n.toLowerCase(Locale.ROOT).startsWith(to.toLowerCase(Locale.ROOT))) { toBox.setValue(n); setFocused(amountBox); return true; }
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && tab == Tab.SEND) {
            if (step == Step.FORM) { review(); return true; }
            if (step == Step.REVIEW) { confirm(); return true; }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        super.renderBackground(g, mx, my, pt);
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, BORDER);
        g.fill(left, top, left + W, top + H, BG);
        g.fillGradient(left, top, left + W, top + 24, HEAD_A, HEAD_B);
        g.fill(left + 8, top + 94, left + W - 8, top + H - 22, PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        State s = WalletClient.last;
        g.drawString(font, "Indigo Wallet", left + 10, top + 8, TEXT);
        String who = minecraft.player == null ? "" : minecraft.player.getGameProfile().getName();
        g.drawString(font, who, left + W - 10 - font.width(who), top + 8, 0xFFD9D5FF);
        g.drawString(font, "BALANCE", left + 12, top + 32, MUTED, false);
        String bal = s == null ? "..." : s.balance();
        g.pose().pushPose();
        g.pose().scale(2f, 2f, 1f);
        g.drawString(font, bal, (left + 12) / 2, (top + 44) / 2, TEXT);
        g.pose().popPose();
        String chip = s != null && s.linked() ? "Discord linked" : "Discord not linked";
        g.drawString(font, chip, left + W - 12 - font.width(chip), top + 32, s != null && s.linked() ? GREEN : MUTED, false);
        int cx = left + 16, cy = top + 98;
        switch (tab) {
            case ACTIVITY -> renderActivity(g, s, cx, cy);
            case SEND -> renderSend(g, cx, cy);
            case DISCORD -> renderDiscord(g, s, cx, cy);
        }
        if (!message.isEmpty()) g.drawCenteredString(font, font.plainSubstrByWidth(message, W - 20), left + W / 2, top + H - 15, messageColor);
    }

    private void renderActivity(GuiGraphics g, State s, int x, int y) {
        if (s == null || s.rows().isEmpty()) {
            g.drawString(font, "No activity yet.", x, y + 4, MUTED, false);
            return;
        }
        g.drawString(font, "WHEN (UTC)", x, y + 1, 0xFF6E6A99, false);
        g.drawString(font, "WHAT", x + 76, y + 1, 0xFF6E6A99, false);
        g.drawString(font, "AMOUNT", left + W - 18 - font.width("AMOUNT"), y + 1, 0xFF6E6A99, false);
        int row = 0;
        for (Row r : s.rows()) {
            if (row >= 7) break;
            int ry = y + 14 + row * 12;
            g.drawString(font, r.when(), x, ry, 0xFF7E7AA8, false);
            String label = r.label() + (r.note().isEmpty() ? "" : " · " + r.note());
            int room = W - 18 - 60 - (x + 76 - left);
            g.drawString(font, font.plainSubstrByWidth(label, room), x + 76, ry, TEXT, false);
            if (!r.amount().isEmpty())
                g.drawString(font, r.amount(), left + W - 18 - font.width(r.amount()), ry, r.incoming() ? GREEN : RED, false);
            row++;
        }
    }

    private void renderSend(GuiGraphics g, int x, int y) {
        if (step == Step.FORM) {
            g.drawString(font, "To", x, y + 4, MUTED, false);
            g.drawString(font, "Amount", x, y + 26, MUTED, false);
            State s = WalletClient.last;
            g.drawString(font, s == null ? "" : s.currency(), x + 154, y + 26, MUTED, false);
            g.drawString(font, "Note", x, y + 48, MUTED, false);
            // right of the name box, clipped to the panel so it can never run off the window
            int hintX = x + 204;
            g.drawString(font, font.plainSubstrByWidth("Tab completes", left + W - 12 - hintX), hintX, y + 4, 0xFF6E6A99, false);
        } else {
            State s = WalletClient.last;
            g.drawString(font, "Please check before you confirm:", x, y + 2, MUTED, false);
            g.drawString(font, "Send " + amount.trim() + " " + (s == null ? "" : s.currency()), x, y + 18, GOLD);
            g.drawString(font, "to " + to.trim(), x, y + 30, GOLD);
            if (!note.isBlank()) g.drawString(font, "Note: " + note.trim(), x, y + 44, TEXT, false);
            g.drawString(font, "The server checks your balance when you confirm.", x, y + 56, 0xFF7E7AA8, false);
        }
    }

    private void renderDiscord(GuiGraphics g, State s, int x, int y) {
        boolean linked = s != null && s.linked();
        if (linked) {
            g.drawString(font, "Your Discord account is linked.", x, y + 2, GREEN);
            g.drawString(font, "The Indigo bot shows and uses this same balance", x, y + 18, TEXT, false);
            g.drawString(font, "for /balance, /transactions and /pay.", x, y + 30, TEXT, false);
            if (confirmUnlink) g.drawString(font, "Unlink it? You can link again with a new code.", x, y + 50, GOLD, false);
            return;
        }
        if (liveCode()) {
            g.drawString(font, "In the Indigo Discord, type:", x, y + 2, MUTED, false);
            String code = "/link " + WalletClient.linkCode;
            g.pose().pushPose();
            g.pose().scale(2f, 2f, 1f);
            g.drawString(font, code, x / 2, (y + 18) / 2, GOLD);
            g.pose().popPose();
            long left_ = Math.max(0, (WalletClient.linkExpiresAt - System.currentTimeMillis()) / 1000);
            g.drawString(font, String.format(Locale.ROOT, "Works once. Expires in %d:%02d. Never share it.", left_ / 60, left_ % 60), x, y + 44, TEXT, false);
            g.drawString(font, "Nothing is linked by name: only this code proves it's you.", x, y + 58, 0xFF7E7AA8, false);
        } else {
            g.drawString(font, "Link your Discord account to see and use this", x, y + 2, TEXT, false);
            g.drawString(font, "same balance from Discord. There is only one balance.", x, y + 14, TEXT, false);
            g.drawString(font, "1. Get a one-time code here.", x, y + 34, MUTED, false);
            g.drawString(font, "2. In the Indigo Discord, type /link and the code.", x, y + 46, MUTED, false);
            g.drawString(font, "Codes expire after a few minutes and work once.", x, y + 58, 0xFF7E7AA8, false);
        }
    }

    @Override
    public void tick() {
        if (tab == Tab.DISCORD && !WalletClient.linkCode.isEmpty() && !liveCode()) {
            WalletClient.linkCode = "";
            rebuildWidgets();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
