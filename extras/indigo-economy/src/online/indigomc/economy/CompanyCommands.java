package online.indigomc.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import online.indigomc.economy.core.CompanyConsole;
import online.indigomc.economy.core.CompanyLedger;
import online.indigomc.economy.net.WalletPayloads;

import java.util.Locale;

/**
 * {@code /company ...}: a thin wrapper around {@link CompanyConsole}, which holds the whole command surface as plain,
 * headless-tested Java. Everything is decided server-side there and in {@code CompanyLedger}; this class only turns
 * the caller into an actor, prints the reply and tells online players about payments. Off unless
 * {@code companies_enabled=true} in config/indigo_economy-server.properties.
 */
final class CompanyCommands {
    private CompanyCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("company")
            .executes(c -> run(c, ""))
            .then(Commands.argument("args", StringArgumentType.greedyString()).executes(c -> run(c, StringArgumentType.getString(c, "args")))));
    }

    private static int run(CommandContext<CommandSourceStack> c, String args) {
        CommandSourceStack src = c.getSource();
        EconomyService s = EconomyService.get();
        if (s == null || !s.ok()) {
            src.sendFailure(Component.literal("The Indigo economy is unavailable right now."));
            return 0;
        }
        if (!s.cfg.companiesEnabled) {
            src.sendFailure(Component.literal("Companies are not enabled on this server yet."));
            return 0;
        }
        ServerPlayer p = src.getPlayer();
        boolean admin = args.trim().toLowerCase(Locale.ROOT).startsWith("admin");
        CompanyLedger.Actor actor;
        if (p == null) actor = admin ? CompanyLedger.Actor.staff("console:" + src.getTextName(), null) : null;
        else if (admin && EconomyCommands.isAdmin(src)) actor = CompanyLedger.Actor.staff("staff:" + p.getUUID(), p.getUUID());
        else actor = CompanyLedger.Actor.player(p.getUUID());
        if (actor == null) {
            src.sendFailure(Component.literal("Run company commands as a player (staff: /company admin ...)."));
            return 0;
        }
        if (p != null && !s.allow(p.getUUID())) {
            src.sendFailure(Component.literal("Slow down a little and try again."));
            return 0;
        }
        String first = args.isBlank() ? "help" : args.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        CompanyConsole.Reply r = s.companies.run(actor, args);
        IndigoEconomy.LOG.info("INDIGO_COMPANY_CMD by={} sub={} ok={}", actor.id(), first, r.ok());
        for (int i = 0; i < r.lines().size(); i++) {
            MutableComponent line = Component.literal(r.lines().get(i))
                .withStyle(i == 0 ? (r.ok() ? ChatFormatting.AQUA : ChatFormatting.RED) : ChatFormatting.GRAY);
            if (i == r.lines().size() - 1 && r.confirm() != null) line.append(Component.literal(" ")).append(button(r.confirm()));
            src.sendSystemMessage(line);
        }
        for (CompanyConsole.Notice n : r.notices()) {
            ServerPlayer o = s.server.getPlayerList().getPlayer(n.player());
            if (o == null) continue;
            o.sendSystemMessage(Component.literal(n.text()).withStyle(ChatFormatting.GREEN));
            s.push(o, false, WalletPayloads.State.INFO, n.text(), "", 0);
        }
        if (p != null && r.ok()) s.push(p, false, WalletPayloads.State.NONE, "", "", 0);   // a deposit or payout changes the wallet
        return r.ok() ? 1 : 0;
    }

    private static MutableComponent button(String command) {
        return Component.literal("[Confirm]").withStyle(st -> st.withBold(true).withColor(ChatFormatting.GREEN)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }
}
