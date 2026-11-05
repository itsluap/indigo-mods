package online.indigomc.economy.casino;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import online.indigomc.economy.EconomyService;
import online.indigomc.economy.IndigoEconomy;
import online.indigomc.economy.core.Ledger;

import java.util.UUID;

/**
 * /casino open|info|deposit|withdraw|close (0.5.0). The screens do the day-to-day work; these cover opening and
 * closing, which aren't tied to a table. With casino_enabled=false (0.5.4) a casino owner can still look up, withdraw
 * from and close their casino, so a house bank is never stuck; opening, depositing and every table stay off.
 */
public final class CasinoCommands {
    private CasinoCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("casino")
            .executes(CasinoCommands::info)
            .then(Commands.literal("open").then(Commands.argument("name", StringArgumentType.greedyString()).executes(CasinoCommands::open)))
            .then(Commands.literal("info").executes(CasinoCommands::info))
            .then(Commands.literal("deposit").then(Commands.argument("amount", StringArgumentType.word()).executes(c -> move(c, true))))
            .then(Commands.literal("withdraw").then(Commands.argument("amount", StringArgumentType.word()).executes(c -> move(c, false))))
            .then(Commands.literal("close").executes(CasinoCommands::closeAsk)
                .then(Commands.literal("confirm").executes(CasinoCommands::close))));
    }

    /** Casinos switched off here (casino_enabled=false): they live on the Indigo roleplay server instead. */
    public static final String OFF = "Casinos have moved to the Indigo roleplay server.";

    private static EconomyService service(CommandSourceStack src) { return service(src, false); }

    /** {@code wind} = a winding-down action (info, withdraw, close) that still works while casinos are switched off. */
    private static EconomyService service(CommandSourceStack src, boolean wind) {
        EconomyService s = EconomyService.get();
        if (s == null || !s.ok()) { src.sendFailure(Component.literal("The Indigo economy is unavailable right now.")); return null; }
        if (!s.cfg.casinoEnabled && !wind) { src.sendFailure(Component.literal(OFF)); return null; }
        return s;
    }

    private static void ok(CommandSourceStack src, String msg) { src.sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.GREEN), false); }

    private static int open(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        EconomyService s = service(c.getSource());
        if (s == null) return 0;
        long fee = s.cfg.casinoLicenceIc * CasinoService.unit(s);
        try {
            Ledger.Casino casino = s.ledger.casinoOpen(p.getUUID(), StringArgumentType.getString(c, "name"), fee, "open:" + UUID.randomUUID());
            IndigoEconomy.LOG.info("INDIGO_CASINO_OPENED casino={} id={} owner={} fee={}", casino.name(), casino.id(), p.getGameProfile().getName(), fee);
            s.push(p, false, online.indigomc.economy.net.WalletPayloads.State.NONE, "", "", 0);
            ok(c.getSource(), casino.name() + " is open! The licence cost " + s.money.format(fee) + ".");
            c.getSource().sendSystemMessage(Component.literal(
                "Next: put money in the house bank (/casino deposit <amount>), then craft and place slot machines, blackjack tables "
                + "and roulette tables. Right-click one to set its bet limits. Players can only bet what the house bank can cover."));
            return 1;
        } catch (Ledger.LedgerError e) {
            c.getSource().sendFailure(Component.literal(e.code.equals("insufficient_funds")
                ? "A casino licence costs " + s.money.format(fee) + ". Your balance is " + s.money.format(s.ledger.balance(p.getUUID())) + "."
                : e.getMessage()));
            return 0;
        }
    }

    private static int info(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        EconomyService s = service(c.getSource(), true);
        if (s == null) return 0;
        var mine = s.ledger.casinoOf(p.getUUID());
        if (mine.isEmpty() && !s.cfg.casinoEnabled) { c.getSource().sendFailure(Component.literal(OFF)); return 0; }
        if (!s.cfg.casinoEnabled) c.getSource().sendSystemMessage(Component.literal(OFF
            + " Your house bank is safe: /casino withdraw <amount> or /casino close still work here.").withStyle(ChatFormatting.YELLOW));
        if (mine.isEmpty()) {
            c.getSource().sendSystemMessage(Component.literal("You don't run a casino. Open one with /casino open <name> (licence "
                + s.money.format(s.cfg.casinoLicenceIc * CasinoService.unit(s)) + "). Bets are capped at "
                + s.money.format(s.cfg.casinoMaxBetIc * CasinoService.unit(s)) + " per round."));
            return 1;
        }
        Ledger.Casino k = mine.get();
        long inPlay = 0;
        for (JsonObject r : s.ledger.openRounds()) if (r.get("casino").getAsString().equals(k.id())) inPlay += r.get("stake").getAsLong() + r.get("reserve").getAsLong();
        c.getSource().sendSystemMessage(Component.literal(k.name()).withStyle(ChatFormatting.GOLD));
        c.getSource().sendSystemMessage(Component.literal("House bank: " + s.money.format(s.ledger.casinoBank(k.id()))
            + (inPlay > 0 ? " (+" + s.money.format(inPlay) + " in play)" : "")));
        c.getSource().sendSystemMessage(Component.literal("Rounds played: " + k.rounds() + ". Bets taken: " + s.money.format(k.staked())
            + ". Paid back: " + s.money.format(k.returned()) + ". House result: " + s.money.signed(k.staked() - k.returned()) + "."));
        return 1;
    }

    private static int move(CommandContext<CommandSourceStack> c, boolean in) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        EconomyService s = service(c.getSource(), !in);
        if (s == null) return 0;
        long amt;
        try { amt = s.money.parse(StringArgumentType.getString(c, "amount")); }
        catch (IllegalArgumentException e) { c.getSource().sendFailure(Component.literal(e.getMessage())); return 0; }
        try {
            if (in) s.ledger.casinoDeposit(p.getUUID(), amt, null); else s.ledger.casinoWithdraw(p.getUUID(), amt, null);
            Ledger.Casino k = s.ledger.casinoOf(p.getUUID()).orElseThrow();
            IndigoEconomy.LOG.info("INDIGO_CASINO_{} casino={} amount={}", in ? "DEPOSIT" : "WITHDRAW", k.name(), amt);
            s.push(p, false, online.indigomc.economy.net.WalletPayloads.State.NONE, "", "", 0);
            ok(c.getSource(), (in ? "Added " : "Took out ") + s.money.format(amt) + (in ? " to " : " from ") + k.name()
                + "'s house bank. It now holds " + s.money.format(s.ledger.casinoBank(k.id())) + ".");
            return 1;
        } catch (Ledger.LedgerError e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int closeAsk(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        EconomyService s = service(c.getSource(), true);
        if (s == null) return 0;
        var mine = s.ledger.casinoOf(p.getUUID());
        if (mine.isEmpty()) { c.getSource().sendFailure(Component.literal("You don't run a casino.")); return 0; }
        c.getSource().sendSystemMessage(Component.literal("Close " + mine.get().name() + "? The house bank ("
            + s.money.format(s.ledger.casinoBank(mine.get().id())) + ") comes back to your wallet, your tables stop working, and the licence is not "
            + "refunded. Type /casino close confirm to close it.").withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int close(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        EconomyService s = service(c.getSource(), true);
        if (s == null) return 0;
        try {
            String name = s.ledger.casinoOf(p.getUUID()).map(Ledger.Casino::name).orElse("");
            Ledger.Result r = s.ledger.casinoClose(p.getUUID(), null);
            Ledger.Posting back = r.tx().postingFor(Ledger.acct(p.getUUID()));
            IndigoEconomy.LOG.info("INDIGO_CASINO_CLOSED casino={} owner={} returned={}", name, p.getGameProfile().getName(), back == null ? 0 : back.amount());
            s.push(p, false, online.indigomc.economy.net.WalletPayloads.State.NONE, "", "", 0);
            ok(c.getSource(), name + " is closed." + (back == null ? "" : " " + s.money.format(back.amount()) + " came back to your wallet."));
            return 1;
        } catch (Ledger.LedgerError e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }
}
