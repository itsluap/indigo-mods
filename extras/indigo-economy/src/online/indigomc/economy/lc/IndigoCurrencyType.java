package online.indigomc.economy.lc;

import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.lightman314.lightmanscurrency.api.capability.money.IMoneyHandler;
import io.github.lightman314.lightmanscurrency.api.money.types.CurrencyType;
import io.github.lightman314.lightmanscurrency.api.money.types.IPlayerMoneyHandler;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValueParser;
import io.github.lightman314.lightmanscurrency.common.util.IClientTracker;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import online.indigomc.economy.core.Money;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.function.Consumer;

/** Registers Indigo Credits as a Lightman's Currency currency type (public LC CurrencyType API). */
public final class IndigoCurrencyType extends CurrencyType {
    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath("indigo_economy", "indigo_credits");
    public static final IndigoCurrencyType INSTANCE = new IndigoCurrencyType();

    private IndigoCurrencyType() { super(TYPE); }

    @Override
    protected MoneyValue sumValuesInternal(List<MoneyValue> values) {
        long total = 0;
        for (MoneyValue v : values) if (v instanceof IndigoCreditValue c) total = Math.addExact(total, c.minor());
        return IndigoCreditValue.of(total);
    }

    @Override public IPlayerMoneyHandler createMoneyHandlerForPlayer(Player player) { return new IndigoPlayerMoneyHandler(player); }

    /** Credits never exist as items or in containers (no coin slots, no ATMs, no money chests). */
    @Override public IMoneyHandler createMoneyHandlerForContainer(Container c, Consumer<ItemStack> overflow, IClientTracker t) { return null; }
    @Override public IMoneyHandler createMoneyHandlerForATM(Player player, Container container) { return null; }

    @Override public MoneyValue loadMoneyValue(CompoundTag tag) { return IndigoCreditValue.load(tag); }
    @Override public MoneyValue loadMoneyValueJson(JsonObject json) { return IndigoCreditValue.load(json); }
    @Override public MoneyValueParser getValueParser() { return Parser.INSTANCE; }

    /** "indigo;12.50" in commands and config. */
    static final class Parser extends MoneyValueParser {
        static final Parser INSTANCE = new Parser();
        private static final Money MONEY = new Money(IndigoCreditValue.DECIMALS, "credit", "credits");

        private Parser() { super("indigo"); }

        @Override
        protected MoneyValue parseValueArgument(@Nonnull StringReader reader) throws CommandSyntaxException {
            String text = reader.getRemaining();
            reader.setCursor(reader.getTotalLength());
            try {
                return IndigoCreditValue.of(MONEY.parse(text));
            } catch (IllegalArgumentException e) {
                throw NO_VALUE_EXCEPTION.createWithContext(reader);
            }
        }

        @Override
        protected String writeValueArgument(@Nonnull MoneyValue value) {
            return value instanceof IndigoCreditValue c ? IndigoCreditValue.plain(c.minor()) : null;
        }
    }
}
