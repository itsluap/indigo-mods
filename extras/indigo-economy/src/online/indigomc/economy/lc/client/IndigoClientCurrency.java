package online.indigomc.economy.lc.client;

import io.github.lightman314.lightmanscurrency.api.money.client.ClientCurrencyType;
import io.github.lightman314.lightmanscurrency.api.money.client.ClientMoneyAPI;
import io.github.lightman314.lightmanscurrency.api.money.input.MoneyInputHandler;
import io.github.lightman314.lightmanscurrency.api.money.input.templates.SimpleDisplayInput;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.client.gui.widget.button.trade.DisplayEntry;
import io.github.lightman314.lightmanscurrency.client.gui.widget.button.trade.display.TextDisplayEntry;
import io.github.lightman314.lightmanscurrency.client.util.TextRenderUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import online.indigomc.economy.lc.IndigoCreditValue;
import online.indigomc.economy.lc.IndigoCurrencyType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** How Indigo Credits look in LC screens: price labels and the price field in the shop editor. */
public final class IndigoClientCurrency extends ClientCurrencyType {
    public static final IndigoClientCurrency INSTANCE = new IndigoClientCurrency();

    private IndigoClientCurrency() { super(IndigoCurrencyType.INSTANCE); }

    public static void init(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(() -> ClientMoneyAPI.getApi().RegisterClientType(INSTANCE)));
    }

    @Override
    public List<MoneyInputHandler> getInputHandlers(@Nullable Player player) {
        List<MoneyInputHandler> list = new ArrayList<>();
        list.add(new Input());
        return list;
    }

    @Override
    public DisplayEntry getDisplayEntry(MoneyValue value, @Nullable List<Component> additionalTooltips, boolean overrideTooltips) {
        if (!(value instanceof IndigoCreditValue v)) return throwIllegalDisplayException(value, IndigoCreditValue.class);
        List<Component> tooltip = new ArrayList<>();
        if (!overrideTooltips) {
            tooltip.add(Component.literal(IndigoCreditValue.plain(v.minor()) + " Indigo Credits").withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.literal("Paid from your Indigo wallet. No coins needed.").withStyle(ChatFormatting.GRAY));
        }
        if (additionalTooltips != null) tooltip.addAll(additionalTooltips);
        return TextDisplayEntry.of(value.getText(Component.empty()), TextRenderUtil.TextFormatting.create(), tooltip);
    }

    /** Price box: "12.50 IC", two decimals. */
    static final class Input extends SimpleDisplayInput {
        Input() { this.setPostfix(" IC"); }

        @Nonnull
        @Override
        protected MoneyValue getValueFromInput(double input) {
            return IndigoCreditValue.of(BigDecimal.valueOf(input).setScale(IndigoCreditValue.DECIMALS, RoundingMode.HALF_UP).movePointRight(IndigoCreditValue.DECIMALS).longValue());
        }

        @Override
        protected double getTextFromDisplay(@Nonnull MoneyValue value) {
            return value instanceof IndigoCreditValue v ? BigDecimal.valueOf(v.minor(), IndigoCreditValue.DECIMALS).doubleValue() : 0;
        }

        @Override protected int getRelevantDecimals() { return IndigoCreditValue.DECIMALS; }
        @Nonnull @Override public Component inputName() { return Component.literal("Indigo Credits"); }
        @Nonnull @Override public String getUniqueName() { return IndigoCurrencyType.TYPE.toString(); }
    }
}
