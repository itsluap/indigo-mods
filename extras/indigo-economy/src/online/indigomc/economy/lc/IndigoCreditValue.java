package online.indigomc.economy.lc;

import com.google.gson.JsonObject;
import io.github.lightman314.lightmanscurrency.api.misc.player.OwnerData;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import online.indigomc.economy.IndigoEconomy;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A price or amount in Indigo Credits for Lightman's Currency. It is only a NUMBER: no LC storage ever
 * holds real Indigo money. Buyers pay the Indigo ledger and shop owners are paid by the ledger
 * ({@link LcShops}). Integer minor units (2 decimals), never floating point.
 */
public final class IndigoCreditValue extends MoneyValue {
    public static final int DECIMALS = 2;
    private final long minor;

    private IndigoCreditValue(long minor) { this.minor = minor; }

    public static MoneyValue of(long minor) { return minor <= 0 ? MoneyValue.empty() : new IndigoCreditValue(minor); }

    public long minor() { return minor; }

    @Nonnull @Override protected ResourceLocation getType() { return IndigoCurrencyType.TYPE; }
    @Override public boolean isEmpty() { return minor <= 0; }
    @Override public long getCoreValue() { return Math.max(0, minor); }

    public static String plain(long minor) {
        return BigDecimal.valueOf(minor, DECIMALS).setScale(DECIMALS, RoundingMode.UNNECESSARY).toPlainString();
    }

    @Override
    public MutableComponent getText(@Nonnull MutableComponent emptyText) {
        if (isEmpty()) return emptyText;
        return Component.literal(plain(minor) + " IC");
    }

    // LC's own empty/free value (e.g. "no taxes") must behave as zero here, as it does in LC's NullValue
    @Override public MoneyValue addValue(@Nonnull MoneyValue v) {
        if (v.isEmpty() || v.isFree()) return this;
        return v instanceof IndigoCreditValue o ? of(Math.addExact(minor, o.minor)) : null;
    }
    @Override public boolean containsValue(@Nonnull MoneyValue q) {
        if (q.isEmpty() || q.isFree()) return true;
        return q instanceof IndigoCreditValue o && minor >= o.minor;
    }
    @Override public MoneyValue subtractValue(@Nonnull MoneyValue v) {
        if (v.isEmpty() || v.isFree()) return this;
        return v instanceof IndigoCreditValue o ? of(minor - o.minor) : null;
    }

    @Override
    public MoneyValue percentageOfValue(int percentage, boolean roundUp) {
        if (percentage == 100) return this;
        if (percentage <= 0) return MoneyValue.free();
        long base = Math.multiplyExact(minor, percentage);
        long result = base / 100 + (roundUp && base % 100 != 0 ? 1 : 0);
        return of(result);
    }

    @Nonnull
    @Override
    public MoneyValue multiplyValue(double multiplier) {
        return of(BigDecimal.valueOf(minor).multiply(BigDecimal.valueOf(multiplier)).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    /**
     * A broken trader never holds real Indigo money (it lives in the ledger), so there is nothing to give back.
     * Returning nothing here means breaking a shop can never mint credits.
     */
    @Nonnull
    @Override
    public List<ItemStack> onBlockBroken(@Nonnull OwnerData owner) {
        IndigoEconomy.LOG.warn("INDIGO_LC stored Indigo value {} found on a broken trader; not paid out (ledger is authoritative)", plain(minor));
        return List.of();
    }

    @Nonnull @Override public MoneyValue getSmallestValue() { return of(1); }
    @Nonnull @Override public MoneyValue fromCoreValue(long value) { return of(value); }

    @Override protected void saveAdditional(@Nonnull CompoundTag tag) { tag.putLong("Minor", minor); }
    @Override protected void writeAdditionalToJson(@Nonnull JsonObject json) { json.addProperty("Minor", minor); }

    public static MoneyValue load(CompoundTag tag) { return of(tag.getLong("Minor")); }
    public static MoneyValue load(JsonObject json) { return of(json.get("Minor").getAsLong()); }
}
