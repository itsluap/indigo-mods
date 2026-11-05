package online.indigomc.economy.lc;

import io.github.lightman314.lightmanscurrency.api.money.MoneyAPI;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import online.indigomc.economy.IndigoEconomy;

/** Loaded only when Lightman's Currency is present (see IndigoEconomy). */
public final class LcIntegration {
    private LcIntegration() {}

    public static void init(IEventBus modBus) {
        modBus.addListener((FMLCommonSetupEvent e) -> e.enqueueWork(() -> {
            MoneyAPI.getApi().RegisterCurrencyType(IndigoCurrencyType.INSTANCE);
            IndigoEconomy.LOG.info("INDIGO_LC currency type {} registered", IndigoCurrencyType.TYPE);
        }));
        LcShops.register();
        online.indigomc.economy.EconomyService.shopProbe = LcCompanyShops::lookedAt;
    }
}
