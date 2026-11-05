package online.indigomc.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import online.indigomc.economy.core.Ledger;
import online.indigomc.economy.core.Links;
import online.indigomc.economy.net.WalletPayloads;

import java.security.SecureRandom;
import java.util.*;

/**
 * Chat-command path to the same actions as the wallet screen, for players without the client
 * mod and for staff. /pay always asks for a click-to-confirm; the confirm token doubles as the
 * idempotency key so a double click sends once.
 */
final class EconomyCommands {
    private record PendingPay(UUID from, String to, String amount, String note, long expires) {}

    private static final Map<String, PendingPay> pending = new HashMap<>();
    private static final SecureRandom random = new SecureRandom();

    private EconomyCommands() {}

    private static final SuggestionProvider<CommandSourceStack> NAMES = (ctx, b) -> {
        EconomyService s = EconomyService.get();
        return SharedSuggestionProvider.suggest(s == null || !s.ok() ? List.of() : s.ledger.names(), b);
    };

    static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> pay = Commands.literal("pay")
            .then(Commands.literal("confirm").then(Commands.argument("token", StringArgumentType.word()).executes(EconomyCommands::confirm)))
            .then(Commands.argument("player", StringArgumentType.word()).suggests(NAMES)
                .then(Commands.argument("amount", StringArgumentType.word())
                    .executes(c -> prompt(c, null))
                    .then(Commands.argument("note", StringArgumentType.greedyString()).executes(c -> prompt(c, StringArgumentType.getString(c, "note"))))));
        d.register(pay);
        d.register(Commands.literal("wallet")
            .executes(EconomyCommands::open)
            .then(Commands.literal("history").executes(EconomyCommands::history))
            .then(Commands.literal("link").executes(EconomyCommands::link))
            .then(Commands.literal("unlink").executes(c -> unlink(c, false))
                .then(Commands.literal("confirm").executes(c -> unlink(c, true))))
            .then(pay));
        d.register(Commands.literal("indigoeco")
            .requires(EconomyCommands::isAdmin)
            .then(Commands.literal("status").executes(EconomyCommands::status))
            .then(Commands.literal("verify").executes(EconomyCommands::verify))
            .then(Commands.literal("balance").then(Commands.argument("player", StringArgumentType.word()).suggests(NAMES).executes(EconomyCommands::adminBalance)))
            .then(Commands.literal("history").then(Commands.argument("player", StringArgumentType.word()).suggests(NAMES).executes(EconomyCommands::adminHistory)))
            .then(Commands.literal("grant").then(Commands.argument("player", StringArgumentType.word()).suggests(NAMES)
                .then(Commands.argument("amount", StringArgumentType.word()).then(Commands.argument("reason", StringArgumentType.greedyString())
                    .executes(c -> adjust(c, 1))))))
            .then(Commands.literal("take").then(Commands.argument("player", StringArgumentType.word()).suggests(NAMES)
                .then(Commands.argument("amount", StringArgumentType.word()).then(Commands.argument("reason", StringArgumentType.greedyString())
                    .executes(c -> adjust(c, -1))))))
            .then(Commands.literal("unlink").then(Commands.argument("player", StringArgumentType.word()).suggests(NAMES)
                .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(EconomyCommands::adminUnlink)))));
    }

    static boolean isAdmin(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        return p == null ? src.hasPermission(4) : PermissionAPI.getPermission(p, IndigoEconomy.ADMIN);
    }

    private static EconomyService svc(CommandSourceStack src) {
        EconomyService s = EconomyService.get();
        if (s == null || !s.ok()) {
            src.sendFailure(Component.literal("The Indigo economy is unavailable right now."));
            return null;
        }
        return s;
    }

    private static String actor(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        return p == null ? "console:" + src.getTextName() : "staff:" + p.getUUID();
    }

    // ---------------------------------------------------------------- player commands

    private static int open(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        EconomyService s = svc(c.getSource());
        if (s == null) return 0;
        if (s.hasUi(p)) {
            s.push(p, true, WalletPayloads.State.NONE, "", "", 0);
            return 1;
        }
        p.sendSystemMessage(Component.literal("Indigo wallet: ").withStyle(ChatFormatting.AQUA)
            .append(Component.literal(s.money.format(s.ledger.balance(p.getUUID()))).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));
        p.sendSystemMessage(Component.literal("/pay <player> <amount>, /wallet history, /wallet link").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int history(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        EconomyService s = svc(c.getSource());
        if (s == null) return 0;
        showHistory(c.getSource(), s, p.getUUID());
        return 1;
    }

    private static void showHistory(CommandSourceStack src, EconomyService s, UUID u) {
        src.sendSystemMessage(Component.literal("Balance " + s.money.format(s.ledger.balance(u)) + ". Recent activity:").withStyle(ChatFormatting.AQUA));
        for (Ledger.Tx tx : s.ledger.history(u, 10)) {
            var r = s.row(tx, u);
            MutableComponent line = Component.literal(r.when() + "  ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(r.label()).withStyle(ChatFormatting.GRAY));
            if (!r.amount().isEmpty())
                line.append(Component.literal("  " + r.amount()).withStyle(r.incoming() ? ChatFormatting.GREEN : ChatFormatting.RED));
            if (!r.note().isEmpty()) line.append(Component.literal("  " + r.note()).withStyle(ChatFormatting.DARK_AQUA));
            src.sendSystemMessage(line);
        }
    }

    private static int link(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        EconomyService s = svc(c.getSource());
        if (s == null) return 0;
        try {
            Links.Issued code = s.links.issue(p.getUUID(), p.getGameProfile().getName());
            IndigoEconomy.LOG.info("INDIGO_ECONOMY_LINK_CODE issued for {} (code not logged)", p.getGameProfile().getName());
            p.sendSystemMessage(Component.literal("Your Discord link code: ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(code.code()).withStyle(st -> st.withBold(true).withColor(ChatFormatting.WHITE)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code.code()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to copy"))))));
            p.sendSystemMessage(Component.literal("In the Indigo Discord, use /link " + code.code() + ". It works once and expires in "
                + s.ttlText() + ". Never share it.").withStyle(ChatFormatting.GRAY));
            return 1;
        } catch (Ledger.LedgerError e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int unlink(CommandContext<CommandSourceStack> c, boolean confirmed) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        EconomyService s = svc(c.getSource());
        if (s == null) return 0;
        if (s.ledger.discordFor(p.getUUID()).isEmpty()) {
            c.getSource().sendFailure(Component.literal("No Discord account is linked."));
            return 0;
        }
        if (!confirmed) {
            p.sendSystemMessage(Component.literal("Unlink your Discord account from your wallet? ").withStyle(ChatFormatting.YELLOW)
                .append(button("[Unlink]", "/wallet unlink confirm", ChatFormatting.RED)));
            return 1;
        }
        try {
            s.ledger.unlink(p.getUUID(), "mc:" + p.getUUID(), "minecraft", "unlinked in game");
            p.sendSystemMessage(Component.literal("Discord unlinked.").withStyle(ChatFormatting.GREEN));
            return 1;
        } catch (Ledger.LedgerError e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int prompt(CommandContext<CommandSourceStack> c, String note) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        EconomyService s = svc(c.getSource());
        if (s == null) return 0;
        String to = StringArgumentType.getString(c, "player"), amount = StringArgumentType.getString(c, "amount");
        Optional<Ledger.Account> target = s.ledger.byName(to);
        long minor;
        try {
            minor = s.money.parse(amount);
        } catch (IllegalArgumentException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        if (target.isEmpty()) {
            c.getSource().sendFailure(Component.literal("No Indigo account called \"" + to + "\". They need to have joined once."));
            return 0;
        }
        byte[] b = new byte[6];
        random.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        synchronized (pending) {
            long now = System.currentTimeMillis();
            pending.values().removeIf(x -> x.expires < now);
            pending.put(token, new PendingPay(p.getUUID(), target.get().name(), amount, note, now + 60_000));
        }
        p.sendSystemMessage(Component.literal("Send " + s.money.format(minor) + " to " + target.get().name()
            + (note == null ? "" : " (\"" + note + "\")") + "? ").withStyle(ChatFormatting.YELLOW)
            .append(button("[Confirm]", "/pay confirm " + token, ChatFormatting.GREEN)));
        return 1;
    }

    private static int confirm(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        EconomyService s = svc(c.getSource());
        if (s == null) return 0;
        String token = StringArgumentType.getString(c, "token");
        PendingPay pp;
        synchronized (pending) { pp = pending.get(token); }
        if (pp == null || !pp.from.equals(p.getUUID()) || pp.expires < System.currentTimeMillis()) {
            c.getSource().sendFailure(Component.literal("That payment prompt has expired. Run /pay again."));
            return 0;
        }
        if (!s.allow(p.getUUID())) {
            c.getSource().sendFailure(Component.literal("Slow down a little and try again."));
            return 0;
        }
        EconomyService.Outcome o = s.send(p, pp.to, pp.amount, pp.note, "minecraft", "cmd:" + token);
        if (o.ok()) {
            p.sendSystemMessage(Component.literal(o.message()).withStyle(ChatFormatting.GREEN));
            s.push(p, false, WalletPayloads.State.NONE, "", "", 0);
        } else {
            c.getSource().sendFailure(Component.literal(o.message()));
        }
        return o.ok() ? 1 : 0;
    }

    private static MutableComponent button(String text, String command, ChatFormatting color) {
        return Component.literal(text).withStyle(st -> st.withBold(true).withColor(color)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    // ---------------------------------------------------------------- staff commands

    private static Optional<Ledger.Account> target(CommandContext<CommandSourceStack> c, EconomyService s) {
        String n = StringArgumentType.getString(c, "player");
        Optional<Ledger.Account> a = s.ledger.byName(n);
        if (a.isEmpty()) c.getSource().sendFailure(Component.literal("No Indigo account called \"" + n + "\"."));
        return a;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        EconomyService s = EconomyService.get();
        if (s == null || s.ledger == null) {
            c.getSource().sendFailure(Component.literal("Economy unavailable: " + (s == null ? "not started" : s.failure)));
            return 0;
        }
        String line = "INDIGO_ECONOMY_STATUS seq=" + s.ledger.seq() + " accounts=" + s.ledger.accountCount() + " supply=" + (-s.ledger.treasury())
            + " available=" + s.ledger.available() + " link_codes_outstanding=" + s.links.outstanding();
        c.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int verify(CommandContext<CommandSourceStack> c) {
        EconomyService s = svc(c.getSource());
        if (s == null) return 0;
        try {
            String r = s.ledger.verify();
            c.getSource().sendSuccess(() -> Component.literal("INDIGO_ECONOMY_VERIFY_OK " + r), false);
            return 1;
        } catch (Exception e) {
            c.getSource().sendFailure(Component.literal("INDIGO_ECONOMY_VERIFY_FAIL " + e.getMessage()));
            return 0;
        }
    }

    private static int adminBalance(CommandContext<CommandSourceStack> c) {
        EconomyService s = svc(c.getSource());
        if (s == null) return 0;
        Optional<Ledger.Account> a = target(c, s);
        if (a.isEmpty()) return 0;
        long bal = s.ledger.balance(a.get().uuid());
        String d = s.ledger.discordFor(a.get().uuid()).map(x -> " discord=linked").orElse(" discord=none");
        c.getSource().sendSuccess(() -> Component.literal("INDIGO_ECONOMY_BALANCE " + a.get().name() + " minor=" + bal + " (" + s.money.format(bal) + ")" + d), false);
        return 1;
    }

    private static int adminHistory(CommandContext<CommandSourceStack> c) {
        EconomyService s = svc(c.getSource());
        if (s == null) return 0;
        Optional<Ledger.Account> a = target(c, s);
        if (a.isEmpty()) return 0;
        showHistory(c.getSource(), s, a.get().uuid());
        return 1;
    }

    private static int adjust(CommandContext<CommandSourceStack> c, int sign) {
        EconomyService s = svc(c.getSource());
        if (s == null) return 0;
        Optional<Ledger.Account> a = target(c, s);
        if (a.isEmpty()) return 0;
        try {
            long amount = s.money.parse(StringArgumentType.getString(c, "amount"));
            String reason = StringArgumentType.getString(c, "reason");
            Ledger.Result r = s.ledger.adjust(a.get().uuid(), sign * amount, reason, "admin", actor(c.getSource()), null);
            IndigoEconomy.LOG.info("INDIGO_ECONOMY_ADJUST tx={} player={} delta={} actor={} reason={}", r.tx().txid(), a.get().name(),
                sign * amount, actor(c.getSource()), reason);
            c.getSource().sendSuccess(() -> Component.literal("INDIGO_ECONOMY_ADJUST_OK " + a.get().name() + " " + s.money.signed(sign * amount)
                + " -> " + s.money.format(s.ledger.balance(a.get().uuid()))), true);
            ServerPlayer p = s.server.getPlayerList().getPlayer(a.get().uuid());
            if (p != null) s.push(p, false, WalletPayloads.State.INFO, "Staff adjustment " + s.money.signed(sign * amount) + ": " + reason, "", 0);
            return 1;
        } catch (IllegalArgumentException | Ledger.LedgerError e) {
            c.getSource().sendFailure(Component.literal("INDIGO_ECONOMY_ADJUST_FAIL " + e.getMessage()));
            return 0;
        }
    }

    private static int adminUnlink(CommandContext<CommandSourceStack> c) {
        EconomyService s = svc(c.getSource());
        if (s == null) return 0;
        Optional<Ledger.Account> a = target(c, s);
        if (a.isEmpty()) return 0;
        try {
            s.ledger.unlink(a.get().uuid(), actor(c.getSource()), "admin", StringArgumentType.getString(c, "reason"));
            c.getSource().sendSuccess(() -> Component.literal("INDIGO_ECONOMY_UNLINK_OK " + a.get().name()), true);
            return 1;
        } catch (Ledger.LedgerError e) {
            c.getSource().sendFailure(Component.literal("INDIGO_ECONOMY_UNLINK_FAIL " + e.getMessage()));
            return 0;
        }
    }
}
