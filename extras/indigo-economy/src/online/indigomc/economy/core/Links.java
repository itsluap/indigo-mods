package online.indigomc.economy.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Proof-of-control linking between a Minecraft UUID and a Discord user ID.
 *
 * <p>The player asks for a code in game; the server keeps only its SHA-256, bound to that UUID,
 * for a short TTL. The official bot redeems it on behalf of the Discord user who typed it. A code
 * works once, a new code replaces the old one, issuing and failed redeems are rate limited, and
 * nothing links by matching names. Codes live in memory only: a restart invalidates them.
 */
public final class Links {
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();   // no 0/O/1/I/L
    public static final int CODE_CHARS = 8;

    public record Pending(UUID uuid, String name, long expiresAt) {}
    public record Issued(String code, long expiresAt) {}

    private final Ledger ledger;
    private final LongSupplier clock;
    private final long ttlMs;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> byHash = new HashMap<>();
    private final Map<UUID, String> hashByPlayer = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> issues = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> failures = new HashMap<>();
    private final int issuePerWindow, failPerWindow;
    private final long windowMs;

    public Links(Ledger ledger, LongSupplier clock, long ttlMs, int issuePerWindow, int failPerWindow, long windowMs) {
        this.ledger = ledger;
        this.clock = clock;
        this.ttlMs = ttlMs;
        this.issuePerWindow = issuePerWindow;
        this.failPerWindow = failPerWindow;
        this.windowMs = windowMs;
    }

    public synchronized Issued issue(UUID uuid, String name) throws Ledger.LedgerError {
        long now = clock.getAsLong();
        if (!ledger.account(uuid).isPresent()) throw new Ledger.LedgerError("no_such_account", "No Indigo account yet.");
        if (ledger.discordFor(uuid).isPresent())
            throw new Ledger.LedgerError("account_already_linked", "Already linked to Discord. Unlink first to link a different account.");
        if (!allow(issues.computeIfAbsent(uuid, k -> new ArrayDeque<>()), now, issuePerWindow))
            throw new Ledger.LedgerError("rate_limited", "Too many link codes. Try again in a few minutes.");
        String old = hashByPlayer.remove(uuid);
        if (old != null) byHash.remove(old);
        char[] c = new char[CODE_CHARS];
        for (int i = 0; i < c.length; i++) c[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        String code = new String(c, 0, 4) + "-" + new String(c, 4, 4);
        String h = hash(normalize(code));
        Pending p = new Pending(uuid, name, now + ttlMs);
        byHash.put(h, p);
        hashByPlayer.put(uuid, h);
        issues.get(uuid).addLast(now);
        return new Issued(code, p.expiresAt());
    }

    /** Redeems a code for a Discord user. Consumes the code whether or not linking then succeeds. */
    public Ledger.Tx redeem(String code, String discordId, String actor) throws Ledger.LedgerError {
        Pending p;
        synchronized (this) {
            long now = clock.getAsLong();
            ArrayDeque<Long> f = failures.computeIfAbsent(discordId, k -> new ArrayDeque<>());
            prune(f, now);
            if (f.size() >= failPerWindow)
                throw new Ledger.LedgerError("rate_limited", "Too many wrong codes. Try again in a few minutes.");
            String h = hash(normalize(code == null ? "" : code));
            p = byHash.remove(h);
            if (p != null) hashByPlayer.remove(p.uuid(), h);
            if (p == null || p.expiresAt() < now) {
                f.addLast(now);
                throw new Ledger.LedgerError("invalid_or_expired_code", "That code is wrong or has expired. Make a new one in game.");
            }
        }
        return ledger.link(p.uuid(), discordId, actor, "discord");
    }

    public synchronized Optional<Pending> pendingFor(UUID uuid) {
        String h = hashByPlayer.get(uuid);
        Pending p = h == null ? null : byHash.get(h);
        return p == null || p.expiresAt() < clock.getAsLong() ? Optional.empty() : Optional.of(p);
    }

    public synchronized void cancel(UUID uuid) {
        String h = hashByPlayer.remove(uuid);
        if (h != null) byHash.remove(h);
    }

    public synchronized int outstanding() {
        long now = clock.getAsLong();
        byHash.values().removeIf(p -> p.expiresAt() < now);
        hashByPlayer.values().retainAll(byHash.keySet());
        return byHash.size();
    }

    private boolean allow(ArrayDeque<Long> d, long now, int max) {
        prune(d, now);
        return d.size() < max;
    }

    private void prune(ArrayDeque<Long> d, long now) {
        while (!d.isEmpty() && d.peekFirst() < now - windowMs) d.removeFirst();
    }

    static String normalize(String code) {
        return code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
