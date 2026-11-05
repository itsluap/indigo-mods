package online.indigomc.economy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import online.indigomc.economy.net.CasinoPayloads.View;

/** Client side of the casino: opens or refreshes the screen for the table the server is talking about. */
public final class CasinoClient {
    private CasinoClient() {}

    /** Called on the client main thread for every casino view. */
    public static void onView(View v) {
        Minecraft mc = Minecraft.getInstance();
        if (v.mode() == View.CLOSE) {
            if (mc.screen instanceof CasinoScreen) mc.setScreen(null);
            if (mc.player != null && !v.message().isEmpty()) mc.player.displayClientMessage(Component.literal(v.message()), true);
            return;
        }
        if (mc.screen instanceof CasinoScreen cs && cs.pos.equals(v.pos())) cs.onView(v);
        else if (v.open() && (mc.screen == null || mc.screen instanceof CasinoScreen)) mc.setScreen(new CasinoScreen(v));
    }
}
