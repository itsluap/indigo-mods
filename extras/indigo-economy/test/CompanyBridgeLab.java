import online.indigomc.economy.core.*;
import online.indigomc.economy.core.Companies.Role;
import online.indigomc.economy.core.CompanyLedger.Actor;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * LAB ONLY: a real ledger + economy bridge with companies on, seeded with Acme Power (owner Olive, employee Eve) and
 * Otto Rail (owner Otto), all three linked to Discord ids. Used by tools/discord/test/company-bridge.e2e.test.ts to drive
 * the bot's /company handlers against the exact Java bridge. Prints one JSON line with the port and a random secret,
 * then serves until stdin closes, then prints the final balances and the ledger's own verify() line.
 *
 * Usage: java CompanyBridgeLab <scratch-dir>
 */
public final class CompanyBridgeLab {
    public static final String OLIVE = "400000000000000001", EVE = "400000000000000005", OTTO = "400000000000000006";

    static UUID u(String n) { return UUID.nameUUIDFromBytes(("bridge-lab:" + n).getBytes(StandardCharsets.UTF_8)); }

    public static void main(String[] args) throws Exception {
        Path p = Path.of(args[0]).resolve("journal.jsonl");
        Files.createDirectories(p.getParent());
        Files.deleteIfExists(p);
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String secret = HexFormat.of().formatHex(raw);
        try (Ledger l = new Ledger(p, 0)) {
            UUID olive = u("olive"), eve = u("eve"), otto = u("otto");
            l.ensureAccount(olive, "Olive", "lab"); l.ensureAccount(eve, "Eve", "lab"); l.ensureAccount(otto, "Otto", "lab");
            l.adjust(olive, 100_000, "lab seed", "admin", "console:lab", null);
            l.link(olive, OLIVE, "lab", "lab"); l.link(eve, EVE, "lab", "lab"); l.link(otto, OTTO, "lab", "lab");
            CompanyLedger c = new CompanyLedger(l);
            String acme = Json.str(c.create(Actor.player(olive), "Acme Power", "Electricity for Pine River", "lab", null).tx().meta(), "company");
            c.invite(Actor.player(olive), acme, eve, Role.EMPLOYEE, "lab");
            c.answerInvite(Actor.player(eve), acme, true, "lab");
            c.deposit(Actor.player(olive), acme, 50_000, "seed", "lab", null);
            String rail = Json.str(c.create(Actor.player(otto), "Otto Rail", null, "lab", null).tx().meta(), "company");
            Links links = new Links(l, System::currentTimeMillis, 300_000, 5, 10, 600_000);
            Bridge.Events none = new Bridge.Events() {
                public void transfer(Ledger.Tx tx, UUID from, UUID to) { }
                public void linked(UUID uuid, boolean linked) { }
            };
            try (Bridge br = new Bridge(l, links, new Money(2, "Indigo Credit", "Indigo Credits"), secret.getBytes(StandardCharsets.UTF_8), none,
                    System::currentTimeMillis, "127.0.0.1", 0, 1000, Runnable::run)) {
                br.companies(true);
                System.out.println(Json.GSON.toJson(Json.obj("port", br.port(), "secret", secret)));
                System.out.flush();
                while (System.in.read() >= 0) { }
            }
            System.out.println(Json.GSON.toJson(Json.obj("acme", l.accountBalance(acme), "rail", l.accountBalance(rail), "eve", l.balance(eve),
                "otto", l.balance(otto), "olive", l.balance(olive), "verify", l.verify())));
        }
    }
}
