import online.indigomc.economy.core.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/**
 * Writes a journal with the ledger API as it exists on main BEFORE companies (0.4.4): accounts, grants, transfers,
 * a Discord link, a game award and personal shop trades. The company test build compiles this file once against
 * the old core sources (from git) to produce a real pre-company journal, and once against the new core, where
 * {@link #sequence} is replayed with the company-aware shop settlement to prove personal shops are unchanged.
 *
 * Usage: java LegacyJournalGen <journal>
 */
public final class LegacyJournalGen {
    public interface Settle { void settle(Ledger l, String hold, UUID owner, String reason) throws Exception; }

    public static UUID u(String n) { return UUID.nameUUIDFromBytes(("legacy:" + n).getBytes(StandardCharsets.UTF_8)); }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--open")) { open(Path.of(args[1])); return; }
        Path p = Path.of(args[0]);
        Files.createDirectories(p.toAbsolutePath().getParent());
        Files.deleteIfExists(p);
        try (Ledger l = new Ledger(p, 0)) {
            sequence(l, (lg, h, o, r) -> lg.settle(h, o, r));
        }
        System.out.println("LEGACY_JOURNAL_WRITTEN " + p);
    }

    /**
     * Rollback check: opens a (copy of a) journal with whatever core this was compiled against and prints the ledger's own
     * verify() plus how much money sits on accounts that core knows nothing about (company:*). Used with the OLD core to
     * show what a jar rollback would see.
     */
    static void open(Path p) throws Exception {
        try (Ledger l = new Ledger(p, 0)) {
            long stranded = 0;
            int companies = 0;
            for (var e : l.balancesSnapshot().entrySet())
                if (e.getKey().startsWith("company:")) { companies++; stranded += e.getValue(); }
            System.out.println("{\"verify\":\"" + l.verify() + "\",\"company_accounts\":" + companies + ",\"company_minor\":" + stranded + "}");
        }
    }

    /** Deterministic apart from txids and timestamps. */
    public static void sequence(Ledger l, Settle settle) throws Exception {
        UUID alice = u("alice"), bob = u("bob"), shopkeeper = u("shopkeeper");
        l.ensureAccount(alice, "Alice", "minecraft");
        l.ensureAccount(bob, "Bob", "minecraft");
        l.ensureAccount(shopkeeper, "Shopkeeper", "minecraft");
        l.adjust(alice, 50_000, "starting grant", "admin", "console:lab", null);
        l.adjust(bob, 20_000, "starting grant", "admin", "console:lab", null);
        l.transfer(alice, bob, 1_250, "rent", "minecraft", "mc:" + alice, "ui:legacy-0001");
        l.link(bob, "300000000000000003", "discord:300000000000000003", "discord");
        l.transfer(bob, alice, 500, "Discord payment", "discord", "discord:300000000000000003", "discord-pay-legacy");
        l.gameAward(bob, 200, "blockle", "2026-09-28", 5_000, 200_000, 2_000, "Discord game: blockle", "discord:300000000000000003", "games-legacy-1");
        for (int i = 1; i <= 3; i++) {
            String hold = "legacy-hold-" + i;
            l.hold(hold, alice, 300 * i, "Shop: " + i + "x Bread", Json.obj("trader", "42", "shop", "Bakery", "item", i + "x Bread", "kind", "shop"));
            settle.settle(l, hold, shopkeeper, "Shop sale: " + i + "x Bread");
        }
        l.hold("legacy-hold-refund", bob, 100, "Shop: 1x Cake", Json.obj("trader", "42", "shop", "Bakery", "item", "1x Cake", "kind", "shop"));
        l.refund("legacy-hold-refund", "Shop trade failed: 1x Cake");
        l.checkpoint("world saved");
        l.hold("legacy-hold-open", bob, 75, "Shop: 1x Pie", Json.obj("trader", "42", "shop", "Bakery", "item", "1x Pie", "kind", "shop"));
    }
}
