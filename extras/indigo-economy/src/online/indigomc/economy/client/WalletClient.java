package online.indigomc.economy.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import online.indigomc.economy.net.WalletPayloads;
import org.lwjgl.glfw.GLFW;

/** Client side of the wallet: a keybind (default Y) and the latest server-sent state. */
public final class WalletClient {
    public static final KeyMapping OPEN = new KeyMapping("key.indigo_economy.wallet", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y,
        "key.categories.indigo_economy");
    static WalletPayloads.State last;
    static String linkCode = "";
    static long linkExpiresAt = 0;

    private WalletClient() {}

    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(OPEN));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            Minecraft mc = Minecraft.getInstance();
            while (OPEN.consumeClick()) {
                if (mc.player == null || mc.getConnection() == null || mc.screen != null) continue;
                if (!((net.neoforged.neoforge.common.extensions.ICommonPacketListener) mc.getConnection()).hasChannel(WalletPayloads.Request.TYPE)) {
                    mc.player.displayClientMessage(Component.literal("This server has no Indigo wallet."), true);
                    continue;
                }
                request(WalletPayloads.Request.OPEN);
            }
        });
    }

    public static WalletPayloads.State lastState() { return last; }
    public static String currentLinkCode() { return linkCode; }

    static void request(int action) {
        PacketDistributor.sendToServer(new WalletPayloads.Request(action));
    }

    /** Called on the client main thread for every server wallet state. */
    public static void onState(WalletPayloads.State s) {
        last = s;
        online.indigomc.economy.ClientBalance.minor = s.balanceMinor();
        if (!s.linkCode().isEmpty()) {
            linkCode = s.linkCode();
            linkExpiresAt = System.currentTimeMillis() + s.linkSeconds() * 1000L;
        }
        if (s.linked()) linkCode = "";
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof WalletScreen w) w.onState(s);
        else if (s.open() && mc.screen == null) mc.setScreen(new WalletScreen());
    }
}
