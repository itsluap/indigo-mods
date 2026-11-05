package online.indigomc.economy.core;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Amounts are always integer minor units ({@code long}); nothing is ever stored as floating point.
 * Display name and decimals are configuration (a human policy decision, lab placeholders only).
 */
public final class Money {
    /** Hard ceiling on any single amount or balance: 10^15 minor units. */
    public static final long MAX = 1_000_000_000_000_000L;

    public final int decimals;
    public final String singular;
    public final String plural;

    public Money(int decimals, String singular, String plural) {
        if (decimals < 0 || decimals > 4) throw new IllegalArgumentException("decimals 0..4");
        this.decimals = decimals;
        this.singular = singular;
        this.plural = plural;
    }

    public String plain(long minor) {
        BigDecimal v = BigDecimal.valueOf(minor, decimals);
        DecimalFormat f = new DecimalFormat(decimals == 0 ? "#,##0" : "#,##0." + "0".repeat(decimals),
            DecimalFormatSymbols.getInstance(Locale.ROOT));
        return f.format(v);
    }

    public String format(long minor) {
        return plain(minor) + " " + (Math.abs(minor) == pow10() ? singular : plural);
    }

    public String signed(long minor) {
        return (minor > 0 ? "+" : minor < 0 ? "-" : "") + plain(Math.abs(minor));
    }

    private long pow10() {
        long p = 1;
        for (int i = 0; i < decimals; i++) p *= 10;
        return p;
    }

    /** Parses a user-typed positive amount like "12", "12.5", "1,250.00". Returns minor units or throws. */
    public long parse(String text) {
        if (text == null) throw new IllegalArgumentException("Enter an amount.");
        String t = text.trim().replace(",", "").replace("_", "");
        if (!t.matches("[0-9]{1,16}(\\.[0-9]+)?"))
            throw new IllegalArgumentException(decimals == 0 ? "Enter a whole number, like 25." : "Enter an amount like 25 or 12.50.");
        if (t.contains(".") && t.length() - t.indexOf('.') - 1 > decimals)
            throw new IllegalArgumentException(decimals == 0 ? "Use a whole number." : "Use at most " + decimals + " decimals.");
        if (decimals == 0 && t.contains(".")) throw new IllegalArgumentException("Use a whole number.");
        BigDecimal v = new BigDecimal(t).movePointRight(decimals);
        if (v.signum() <= 0) throw new IllegalArgumentException("The amount must be more than zero.");
        if (v.compareTo(BigDecimal.valueOf(MAX)) > 0) throw new IllegalArgumentException("That amount is too large.");
        return v.longValueExact();
    }
}
