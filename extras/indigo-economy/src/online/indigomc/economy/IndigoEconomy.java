package online.indigomc.economy;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import online.indigomc.economy.net.WalletPayloads;
import org.slf4j.Logger;

/**
 * Indigo account economy. One authoritative, UUID-keyed ledger with an in-game
 * wallet screen and a narrow authenticated bridge for the official Discord bot.
 *
 * <p>Since Indigo 0.4.0 the mod is required on clients (wallet screen and shop price display).
 */
@Mod(WalletPayloads.MODID)
public final class IndigoEconomy {
    public static final Logger LOG = LogUtils.getLogger();

    public static final PermissionNode<Boolean> ADMIN = new PermissionNode<>(WalletPayloads.MODID, "admin", PermissionTypes.BOOLEAN,
        (player, uuid, ctx) -> player != null && player.hasPermissions(3));

    public IndigoEconomy(IEventBus modBus, ModContainer container) {
        modBus.addListener(IndigoEconomy::payloads);
        online.indigomc.economy.casino.CasinoBlocks.init(modBus);
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent e) -> EconomyService.start(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> EconomyService.stop());
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> {
            EconomyCommands.register(e.getDispatcher());
            online.indigomc.economy.casino.CasinoCommands.register(e.getDispatcher());
            CompanyCommands.register(e.getDispatcher());
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (EconomyService.get() != null) online.indigomc.economy.casino.CasinoService.playerLeft(e.getEntity().getUUID());
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> {
            if (EconomyService.get() != null) online.indigomc.economy.casino.CasinoService.serverStopping();
        });
        NeoForge.EVENT_BUS.addListener((PermissionGatherEvent.Nodes e) -> e.addNodes(ADMIN));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && !(p instanceof FakePlayer) && EconomyService.get() != null)
                EconomyService.get().onLogin(p);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Save e) -> {
            if (e.getLevel() instanceof net.minecraft.server.level.ServerLevel l && l.dimension() == net.minecraft.world.level.Level.OVERWORLD
                && EconomyService.get() != null) EconomyService.get().worldSaved();
        });
        boolean lc = ModList.get().isLoaded("lightmanscurrency");
        if (lc) online.indigomc.economy.lc.LcIntegration.init(modBus);
        if (FMLEnvironment.dist.isClient()) {
            online.indigomc.economy.client.WalletClient.init(modBus);
            if (lc) online.indigomc.economy.lc.client.IndigoClientCurrency.init(modBus);
        }
    }

    private static void payloads(RegisterPayloadHandlersEvent e) {
        // Optional at the network level so that an outdated client is not refused with NeoForge's generic
        // "Incompatible client" text; EconomyService.onLogin turns it away with a clear "update the pack" message.
        PayloadRegistrar r = e.registrar("3").optional();
        r.playToServer(WalletPayloads.Request.TYPE, WalletPayloads.Request.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            EconomyService s = EconomyService.get();
            if (s != null && ctx.player() instanceof ServerPlayer sp) s.handleRequest(sp, p.action());
        }));
        r.playToServer(WalletPayloads.Transfer.TYPE, WalletPayloads.Transfer.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            EconomyService s = EconomyService.get();
            if (s != null && ctx.player() instanceof ServerPlayer sp) {
                if (!s.ok()) sp.sendSystemMessage(s.unavailable());
                else s.handleTransfer(sp, p);
            }
        }));
        r.playToClient(WalletPayloads.State.TYPE, WalletPayloads.State.CODEC,
            (p, ctx) -> ctx.enqueueWork(() -> online.indigomc.economy.client.WalletClient.onState(p)));
        // casinos (0.5.0)
        r.playToServer(online.indigomc.economy.net.CasinoPayloads.Action.TYPE, online.indigomc.economy.net.CasinoPayloads.Action.CODEC,
            (p, ctx) -> ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) online.indigomc.economy.casino.CasinoService.action(sp, p);
            }));
        r.playToClient(online.indigomc.economy.net.CasinoPayloads.View.TYPE, online.indigomc.economy.net.CasinoPayloads.View.CODEC,
            (p, ctx) -> ctx.enqueueWork(() -> online.indigomc.economy.client.CasinoClient.onView(p)));
    }
}
