import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import online.indigomc.economy.core.*;
import online.indigomc.economy.core.Companies.*;
import online.indigomc.economy.core.CompanyLedger.Actor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless tests of Indigo companies (milestone 1) against the exact core classes shipped in the mod (no Minecraft).
 * Usage: java CompanyTest <scratch-dir> [<legacy-journal>]   -> runs every test, prints JSON, exit 0 when all pass
 *        java CompanyTest --crash-child <journal>            -> company workload used by the hard-kill test
 * Journals that must verify cleanly are listed in <scratch-dir>/verify-list.txt for the independent Python verifier.
 */
public final class CompanyTest {
    static final Money M = new Money(2, "credit", "credits");
    static final List<Map<String, Object>> results = new ArrayList<>();
    static final List<Path> toVerify = new ArrayList<>();
    static Path dir, legacy;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--crash-child")) { crashChild(Path.of(args[1])); return; }
        dir = Path.of(args[0]);
        legacy = args.length > 1 ? Path.of(args[1]) : null;
        run("identity_and_unique_names", CompanyTest::identity);
        run("role_permission_matrix", CompanyTest::matrix);
        run("roles_enforced_on_operations", CompanyTest::roles);
        run("invites_and_ownership_safety", CompanyTest::ownership);
        run("treasury_transfers_all_directions", CompanyTest::treasury);
        run("treasury_idempotency_and_replay", CompanyTest::treasuryIdem);
        run("concurrent_company_payments", CompanyTest::concurrentPayments);
        run("payroll_atomic_idempotent", CompanyTest::payroll);
        run("payroll_concurrent_duplicate_runs", CompanyTest::payrollConcurrent);
        run("invoices_pay_once_linked_to_tx", CompanyTest::invoices);
        run("company_shop_routing", CompanyTest::shops);
        run("company_shop_concurrent_sales_and_restart", CompanyTest::shopConcurrency);
        run("dissolution", CompanyTest::dissolution);
        run("rejected_actions_change_nothing", CompanyTest::rejectsChangeNothing);
        run("restart_replay_identical_state", CompanyTest::restart);
        run("torn_tail_with_company_events", CompanyTest::torn);
        run("tamper_company_event_fails_closed", CompanyTest::tamper);
        run("hard_kill_during_company_activity", CompanyTest::hardKill);
        run("bridge_company_api", CompanyTest::bridge);
        run("chat_console_commands", CompanyTest::console);
        run("existing_journal_migration_and_personal_shops_unchanged", CompanyTest::migration);
        Files.write(dir.resolve("verify-list.txt"), toVerify.stream().map(Path::toString).toList());
        long failed = results.stream().filter(r -> !(Boolean) r.get("pass")).count();
        JsonObject out = new JsonObject();
        out.add("results", Json.GSON.toJsonTree(results));
        out.addProperty("passed", results.size() - failed);
        out.addProperty("failed", failed);
        System.out.println(Json.GSON.toJson(out));
        System.out.println(failed == 0 ? "COMPANY_TESTS_PASS" : "COMPANY_TESTS_FAIL");
        System.exit(failed == 0 ? 0 : 1);
    }

    interface T { Object run() throws Exception; }

    static void run(String name, T t) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        long t0 = System.nanoTime();
        try {
            r.put("detail", t.run());
            r.put("pass", true);
        } catch (Throwable e) {
            r.put("pass", false);
            r.put("detail", e.toString());
            e.printStackTrace();
        }
        r.put("ms", (System.nanoTime() - t0) / 1_000_000);
        results.add(r);
    }

    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }

    static Path path(String name) throws Exception {
        Path p = dir.resolve(name).resolve("journal.jsonl");
        Files.createDirectories(p.getParent());
        Files.deleteIfExists(p);
        return p;
    }

    /** A fresh ledger whose journal must end up valid (checked again by the Python verifier). */
    static Ledger fresh(String name) throws Exception {
        Path p = path(name);
        toVerify.add(p);
        return new Ledger(p, 0);
    }

    static UUID u(String n) { return UUID.nameUUIDFromBytes(("company-test:" + n).getBytes(StandardCharsets.UTF_8)); }

    static String expectError(T t) throws Exception {
        try { t.run(); } catch (Ledger.LedgerError e) { return e.code; }
        throw new AssertionError("expected a LedgerError");
    }

    /** The cast of most tests: Olive (owner) founds Acme Power; Dan director, Mia manager, Abe accountant, Eve employee; Otto outsider. */
    static final class World {
        final Ledger l;
        final CompanyLedger c;
        final UUID olive = u("olive"), dan = u("dan"), mia = u("mia"), abe = u("abe"), eve = u("eve"), otto = u("otto");
        final Actor O = Actor.player(olive), D = Actor.player(dan), Mi = Actor.player(mia), A = Actor.player(abe), E = Actor.player(eve), X = Actor.player(otto);
        final String acme;

        World(Ledger l) throws Exception {
            this.l = l;
            this.c = new CompanyLedger(l);
            String[] names = {"Olive", "Dan", "Mia", "Abe", "Eve", "Otto"};
            UUID[] us = {olive, dan, mia, abe, eve, otto};
            for (int i = 0; i < us.length; i++) {
                l.ensureAccount(us[i], names[i], "test");
                l.adjust(us[i], 100_000, "seed", "admin", "console:test", null);
            }
            acme = Json.str(c.create(O, "Acme Power", "Electricity for Pine River", "minecraft", null).tx().meta(), "company");
            join(dan, Role.DIRECTOR);
            join(mia, Role.MANAGER);
            join(abe, Role.ACCOUNTANT);
            join(eve, Role.EMPLOYEE);
        }

        void join(UUID who, Role role) throws Exception {
            c.invite(O, acme, who, role, "test");
            c.answerInvite(Actor.player(who), acme, true, "test");
        }

        Company co() { return c.state().get(acme).orElseThrow(); }
        long bal() { return l.accountBalance(acme); }
    }

    static String snapshot(Ledger l) { return l.seq() + "|" + l.balancesSnapshot() + "|" + l.companies().digest(); }

    // ------------------------------------------------------------------ identity

    static String identity() throws Exception {
        try (Ledger l = fresh("identity")) {
            World w = new World(l);
            Company acme = w.co();
            check(acme.id().matches("company:[0-9a-f\\-]{36}") && acme.founder().equals(w.olive) && acme.status() == Status.ACTIVE, "identity " + acme);
            check(acme.members().get(w.olive).role() == Role.OWNER && acme.members().size() == 5, "founder is owner");
            check(l.accountBalance(acme.id()) == 0 && l.balancesSnapshot().containsKey(acme.id()) == false, "treasury starts empty (no balance row until money moves)");
            List<String> codes = new ArrayList<>();
            codes.add(expectError(() -> w.c.create(w.D, "ACME   power", null, "minecraft", null)));
            codes.add(expectError(() -> w.c.create(w.D, "  acme power ", null, "minecraft", null)));
            codes.add(expectError(() -> w.c.create(w.D, "x", null, "minecraft", null)));
            codes.add(expectError(() -> w.c.create(w.D, "company:1234", null, "minecraft", null)));
            codes.add(expectError(() -> w.c.create(w.D, "Bad<Name>", null, "minecraft", null)));
            codes.add(expectError(() -> w.c.create(new Actor("console:x", null, false), "Consoleco", null, "minecraft", null)));
            check(codes.equals(List.of("name_taken", "name_taken", "invalid_name", "invalid_name", "invalid_name", "forbidden")), "codes " + codes);
            String rail = Json.str(w.c.create(w.D, "Dan's Railroad", null, "minecraft", null).tx().meta(), "company");
            check(expectError(() -> w.c.rename(w.D, rail, "acme POWER", "minecraft")).equals("name_taken"), "rename collision");
            w.c.rename(w.D, rail, "Pine River Rail & Freight", "minecraft");
            check(w.c.state().byName("pine river rail & freight").orElseThrow().id().equals(rail) && w.c.state().byName("Dan's Railroad").isEmpty(), "rename moves the key");
            w.c.create(w.D, "Dan's Railroad", null, "minecraft", null);   // old name is free again after a rename
            // idempotent create
            Ledger.Result a = w.c.create(w.X, "Otto Mining", null, "discord", "create-otto-1");
            Ledger.Result b = w.c.create(w.X, "Otto Mining", null, "discord", "create-otto-1");
            check(!a.replayed() && b.replayed() && a.tx().txid().equals(b.tx().txid()), "create replay");
            for (int i = 2; i <= Companies.MAX_OWNED; i++) w.c.create(w.X, "Otto Co " + i, null, "minecraft", null);
            check(expectError(() -> w.c.create(w.X, "Otto Co Extra", null, "minecraft", null)).equals("limit_reached"), "owner limit");
            check(expectError(() -> w.c.invite(w.O, "company:00000000-0000-0000-0000-000000000000", w.otto, Role.EMPLOYEE, "t")).equals("no_such_company"), "forged company id");
            return "company:<uuid> id, founder=owner, case-insensitive unique names " + codes + ", rename frees old name, create idempotent, "
                + Companies.MAX_OWNED + "-company owner limit; " + l.verify();
        }
    }

    // ------------------------------------------------------------------ roles

    static String matrix() {
        // expected[role][perm]: VIEW, VIEW_FINANCE, PAY, PAYROLL_EDIT, INVOICE, SHOPS, MEMBERS, GOVERN
        Map<Role, String> expected = Map.of(
            Role.OWNER, "11111111",
            Role.DIRECTOR, "11111110",
            Role.MANAGER, "11001110",
            Role.ACCOUNTANT, "11001000",
            Role.EMPLOYEE, "10000000");
        StringBuilder table = new StringBuilder();
        for (Role r : Role.values()) {
            StringBuilder got = new StringBuilder();
            for (Perm p : Perm.values()) got.append(Companies.can(new Member(UUID.randomUUID(), r, false, "t"), p) ? '1' : '0');
            check(got.toString().equals(expected.get(r)), r + " " + got);
            table.append(r.label()).append('=').append(got).append(' ');
        }
        check(Companies.can(new Member(UUID.randomUUID(), Role.ACCOUNTANT, true, "t"), Perm.PAY), "granted accountant pays");
        check(!Companies.can(new Member(UUID.randomUUID(), Role.EMPLOYEE, true, "t"), Perm.PAY), "grant means nothing for employees");
        check(!Companies.can(null, Perm.VIEW), "non-member sees nothing");
        check(Companies.canManage(Role.DIRECTOR, Role.MANAGER) && !Companies.canManage(Role.DIRECTOR, Role.DIRECTOR) && !Companies.canManage(Role.DIRECTOR, Role.OWNER), "director scope");
        check(Companies.canManage(Role.MANAGER, Role.EMPLOYEE) && !Companies.canManage(Role.MANAGER, Role.ACCOUNTANT), "manager scope");
        check(!Companies.canManage(Role.ACCOUNTANT, Role.EMPLOYEE) && !Companies.canManage(Role.EMPLOYEE, Role.EMPLOYEE), "no scope");
        return "perms [VIEW,VIEW_FINANCE,PAY,PAYROLL_EDIT,INVOICE,SHOPS,MEMBERS,GOVERN]: " + table.toString().trim()
            + "; accountant PAY only with grant; manage scopes owner>all, director>manager/accountant/employee, manager>employee";
    }

    static String roles() throws Exception {
        try (Ledger l = fresh("roles")) {
            World w = new World(l);
            w.c.deposit(w.O, w.acme, 10_000, "seed", "minecraft", null);
            String p = Ledger.acct(w.otto);
            List<String> denied = new ArrayList<>();
            denied.add("employee pay:" + expectError(() -> w.c.pay(w.E, w.acme, p, 1, "x", "minecraft", null)));
            denied.add("employee balance:" + expectError(() -> w.c.check(w.E, w.acme, Perm.VIEW_FINANCE)));
            denied.add("employee invite:" + expectError(() -> w.c.invite(w.E, w.acme, w.otto, Role.EMPLOYEE, "t")));
            denied.add("manager pay:" + expectError(() -> w.c.pay(w.Mi, w.acme, p, 1, "x", "minecraft", null)));
            denied.add("manager invite director:" + expectError(() -> w.c.invite(w.Mi, w.acme, w.otto, Role.DIRECTOR, "t")));
            denied.add("manager remove accountant:" + expectError(() -> w.c.remove(w.Mi, w.acme, w.abe, "t")));
            denied.add("accountant pay (no grant):" + expectError(() -> w.c.pay(w.A, w.acme, p, 1, "x", "minecraft", null)));
            denied.add("accountant payroll edit:" + expectError(() -> w.c.setPayroll(w.A, w.acme, w.eve, 100, "t")));
            denied.add("director make owner:" + expectError(() -> w.c.setRole(w.D, w.acme, w.mia, Role.OWNER, "t")));
            denied.add("director demote self peer:" + expectError(() -> w.c.setRole(w.D, w.acme, w.dan, Role.MANAGER, "t")));
            denied.add("director rename:" + expectError(() -> w.c.rename(w.D, w.acme, "Dan Power", "t")));
            denied.add("director dissolve:" + expectError(() -> w.c.dissolve(w.D, w.acme, Ledger.acct(w.dan), "x", "t", null)));
            denied.add("outsider balance:" + expectError(() -> w.c.check(w.X, w.acme, Perm.VIEW)));
            denied.add("outsider pay:" + expectError(() -> w.c.pay(w.X, w.acme, p, 1, "x", "minecraft", null)));
            denied.add("forged actor (right id, wrong player):" + expectError(() -> w.c.pay(new Actor("mc:" + w.olive, w.otto, false), w.acme, p, 1, "x", "minecraft", null)));
            denied.add("non-staff admin op:" + expectError(() -> w.c.setSuspended(w.O, w.acme, true, "x", "t")));
            for (String d : denied) check(d.endsWith(":forbidden") || d.endsWith(":not_member"), d);
            check(w.bal() == 10_000, "no money moved");
            // what the roles CAN do
            w.c.invite(w.Mi, w.acme, w.otto, Role.EMPLOYEE, "t");
            w.c.grantPay(w.D, w.acme, w.abe, true, "t");
            w.c.pay(w.A, w.acme, p, 100, "granted accountant", "minecraft", null);
            w.c.grantPay(w.O, w.acme, w.abe, false, "t");
            check(expectError(() -> w.c.pay(w.A, w.acme, p, 1, "x", "minecraft", null)).equals("forbidden"), "grant revoked");
            w.c.setRole(w.D, w.acme, w.eve, Role.ACCOUNTANT, "t");
            check(!w.co().members().get(w.eve).payGrant(), "a role change never carries a grant");
            w.c.pay(w.D, w.acme, p, 200, "director pays", "minecraft", null);
            check(w.bal() == 9_700, "allowed payments moved money");
            return denied.size() + " unauthorised actions rejected (" + String.join(", ", denied) + "); manager invites employee, director grants accountant, granted accountant pays, grant revocable; " + l.verify();
        }
    }

    static String ownership() throws Exception {
        try (Ledger l = fresh("ownership")) {
            World w = new World(l);
            List<String> codes = new ArrayList<>();
            codes.add(expectError(() -> w.c.leave(w.O, w.acme, "t")));
            codes.add(expectError(() -> w.c.setRole(w.O, w.acme, w.olive, Role.DIRECTOR, "t")));
            codes.add(expectError(() -> w.c.setRole(Actor.staff("staff:x", null), w.acme, w.olive, Role.EMPLOYEE, "t")));
            check(codes.equals(List.of("last_owner", "last_owner", "last_owner")), "codes " + codes);
            // invitation flow: someone else's invitation can't be accepted; decline and revoke
            w.c.invite(w.O, w.acme, w.otto, Role.EMPLOYEE, "t");
            check(expectError(() -> w.c.answerInvite(Actor.player(u("nobody")), w.acme, true, "t")).equals("no_invite"), "forged accept");
            w.c.answerInvite(w.X, w.acme, false, "t");
            check(!w.co().invites().containsKey(w.otto) && !w.co().members().containsKey(w.otto), "declined");
            w.c.invite(w.O, w.acme, w.otto, Role.EMPLOYEE, "t");
            w.c.revokeInvite(w.Mi, w.acme, w.otto, "t");
            check(expectError(() -> w.c.answerInvite(w.X, w.acme, true, "t")).equals("no_invite"), "revoked invite can't be accepted");
            check(expectError(() -> w.c.invite(w.O, w.acme, w.dan, Role.EMPLOYEE, "t")).equals("already_member"), "already member");
            // ownership transfer: promote, then the founder leaves
            w.c.setRole(w.O, w.acme, w.dan, Role.OWNER, "t");
            check(w.co().owners() == 2, "two owners");
            w.c.leave(w.O, w.acme, "t");
            check(w.co().owners() == 1 && !w.co().members().containsKey(w.olive) && w.co().founder().equals(w.olive), "founder left; founder record kept");
            check(expectError(() -> w.c.remove(w.Mi, w.acme, w.dan, "t")).equals("forbidden"), "manager can't remove owner");
            check(expectError(() -> w.c.leave(w.D, w.acme, "t")).equals("last_owner"), "new sole owner can't leave");
            // staff recovery: the owner is gone for good -> staff add an owner, audited with a reason
            check(expectError(() -> w.c.staffAddOwner(w.O, w.acme, w.mia, "x", "t")).equals("forbidden"), "players can't use recovery");
            check(expectError(() -> w.c.staffAddOwner(Actor.staff("staff:s", null), w.acme, w.mia, " ", "t")).equals("reason_required"), "reason required");
            Ledger.Tx rec = w.c.staffAddOwner(Actor.staff("console:ops", null), w.acme, w.mia, "owner inactive 90 days, ticket #12", "admin");
            check(w.co().members().get(w.mia).role() == Role.OWNER && rec.actor().equals("console:ops") && rec.reason().contains("ticket #12"), "recovery audited");
            // history of membership changes is in the company's journal history
            long memberEvents = l.history(w.acme, 200).stream().filter(t -> t.type().startsWith("company_")).count();
            check(memberEvents >= 12, "membership changes audited " + memberEvents);
            return "last owner can't leave/demote/be demoted " + codes + "; invite accept/decline/revoke; ownership transferred by promote+leave; staff recovery with reason; "
                + memberEvents + " audited membership events; " + l.verify();
        }
    }

    // ------------------------------------------------------------------ treasury

    static String treasury() throws Exception {
        try (Ledger l = fresh("treasury")) {
            World w = new World(l);
            String rail = Json.str(w.c.create(w.X, "Otto Rail", null, "minecraft", null).tx().meta(), "company");
            Ledger.Result dep = w.c.deposit(w.E, w.acme, 5_000, "investment", "minecraft", null);   // player -> company (any account holder)
            check(w.bal() == 5_000 && l.balance(w.eve) == 95_000, "player -> company");
            check(dep.tx().type().equals("company_transfer") && dep.tx().postings().size() == 2, "one double-entry line");
            w.c.pay(w.O, w.acme, Ledger.acct(w.otto), 1_200, "ore delivery", "minecraft", null);   // company -> player
            check(w.bal() == 3_800 && l.balance(w.otto) == 101_200, "company -> player");
            w.c.pay(w.D, w.acme, rail, 800, "freight", "minecraft", null);                         // company -> company
            check(w.bal() == 3_000 && l.accountBalance(rail) == 800, "company -> company");
            w.c.pay(w.X, rail, w.acme, 300, "power bill", "minecraft", null);
            check(w.bal() == 3_300 && l.accountBalance(rail) == 500, "other direction");
            long seq = l.seq();
            List<String> codes = new ArrayList<>();
            codes.add(expectError(() -> w.c.pay(w.O, w.acme, Ledger.acct(w.otto), 3_301, "x", "minecraft", null)));
            codes.add(expectError(() -> w.c.pay(w.O, w.acme, w.acme, 1, "x", "minecraft", null)));
            codes.add(expectError(() -> w.c.pay(w.O, w.acme, Ledger.acct(u("ghost")), 1, "x", "minecraft", null)));
            codes.add(expectError(() -> w.c.pay(w.O, w.acme, "system:treasury", 1, "x", "minecraft", null)));
            codes.add(expectError(() -> w.c.pay(w.O, w.acme, Ledger.acct(w.otto), 0, "x", "minecraft", null)));
            codes.add(expectError(() -> w.c.pay(w.O, w.acme, Ledger.acct(w.otto), -5, "x", "minecraft", null)));
            codes.add(expectError(() -> w.c.deposit(w.E, w.acme, 1_000_000, "x", "minecraft", null)));
            check(codes.equals(List.of("insufficient_funds", "same_account", "no_such_account", "no_such_account", "invalid_amount", "invalid_amount", "insufficient_funds")), "codes " + codes);
            check(l.seq() == seq, "rejections commit nothing");
            // suspension freezes spending, not receiving
            w.c.setSuspended(Actor.staff("staff:s", null), w.acme, true, "audit", "admin");
            check(expectError(() -> w.c.pay(w.O, w.acme, Ledger.acct(w.otto), 1, "x", "minecraft", null)).equals("company_inactive"), "suspended can't spend");
            w.c.deposit(w.E, w.acme, 100, "still accepted", "minecraft", null);
            w.c.setSuspended(Actor.staff("staff:s", null), w.acme, false, "audit done", "admin");
            w.c.pay(w.O, w.acme, Ledger.acct(w.otto), 1, "after unsuspend", "minecraft", null);
            // company history is journal-derived, and a player's history shows company payments too
            List<Ledger.Tx> h = l.history(w.acme, 50);
            check(h.stream().anyMatch(t -> t.type().equals("company_transfer") && t.postingFor(w.acme).amount() == -1_200), "company history");
            check(l.history(w.otto, 5).stream().anyMatch(t -> t.type().equals("company_transfer")), "player history");
            long sum = 0;
            for (long v : l.balancesSnapshot().values()) sum += v;
            check(sum == 0, "double entry");
            return "player->company, company->player, company->company both ways; rejections " + codes + " commit nothing; suspended = receive-only; "
                + "treasury " + M.format(w.bal()) + "; " + l.verify();
        }
    }

    static String treasuryIdem() throws Exception {
        try (Ledger l = fresh("treasury-idem")) {
            World w = new World(l);
            w.c.deposit(w.O, w.acme, 10_000, "seed", "minecraft", null);
            Ledger.Result a = w.c.pay(w.O, w.acme, Ledger.acct(w.eve), 250, "bonus", "discord", "discord-cpay-0001");
            Ledger.Result b = w.c.pay(w.D, w.acme, Ledger.acct(w.eve), 250, "bonus", "discord", "discord-cpay-0001");
            check(!a.replayed() && b.replayed() && a.tx().txid().equals(b.tx().txid()) && w.bal() == 9_750, "replayed pay moves money once");
            check(expectError(() -> w.c.pay(w.O, w.acme, Ledger.acct(w.eve), 251, "bonus", "discord", "discord-cpay-0001")).equals("idempotency_conflict"), "conflict");
            Ledger.Result c = w.c.pay(w.O, w.acme, Ledger.acct(w.eve), 250, "bonus", "minecraft", "discord-cpay-0001");
            check(!c.replayed() && w.bal() == 9_500, "keys are per source");
            Ledger.Result d1 = w.c.deposit(w.E, w.acme, 70, "dues", "minecraft", "ui:dep-1"), d2 = w.c.deposit(w.E, w.acme, 70, "dues", "minecraft", "ui:dep-1");
            check(d2.replayed() && d1.tx().txid().equals(d2.tx().txid()) && w.bal() == 9_570, "deposit replay");
            return "replay returns original txid " + a.tx().txid().substring(0, 8) + ", conflict rejected, per-source keys, deposit replay; " + l.verify();
        }
    }

    static String concurrentPayments() throws Exception {
        try (Ledger l = fresh("concurrent-payments")) {
            World w = new World(l);
            String rail = Json.str(w.c.create(w.X, "Otto Rail", null, "minecraft", null).tx().meta(), "company");
            w.c.deposit(w.O, w.acme, 50_000, "seed", "minecraft", null);
            w.c.deposit(w.X, rail, 50_000, "seed", "minecraft", null);
            long seq0 = l.seq();
            AtomicInteger ok = new AtomicInteger(), insufficient = new AtomicInteger(), other = new AtomicInteger();
            ExecutorService ex = Executors.newFixedThreadPool(12);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 12; t++) {
                final int seed = t;
                fs.add(ex.submit(() -> {
                    Random r = new Random(seed);
                    for (int i = 0; i < 400; i++) {
                        long amt = 1 + r.nextInt(3_000);
                        try {
                            switch (r.nextInt(5)) {
                                case 0 -> w.c.pay(w.O, w.acme, rail, amt, "load", "test", null);
                                case 1 -> w.c.pay(w.X, rail, w.acme, amt, "load", "test", null);
                                case 2 -> w.c.pay(w.D, w.acme, Ledger.acct(w.eve), amt, "load", "test", null);
                                case 3 -> w.c.deposit(w.E, w.acme, amt, "load", "test", null);
                                default -> l.transfer(w.eve, w.otto, amt, "load", "test", "t", null);
                            }
                            ok.incrementAndGet();
                        } catch (Ledger.LedgerError e) {
                            if (e.code.equals("insufficient_funds")) insufficient.incrementAndGet(); else other.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
            ex.shutdown();
            long total = 0;
            for (Map.Entry<String, Long> e : l.balancesSnapshot().entrySet()) {
                if (!e.getKey().equals(Ledger.TREASURY)) { check(e.getValue() >= 0, "negative " + e); total += e.getValue(); }
            }
            check(total == -l.treasury(), "conservation");
            check(other.get() == 0, "unexpected errors " + other.get());
            check(l.seq() - seq0 == ok.get(), "one journal line per committed payment");
            return ok.get() + " committed, " + insufficient.get() + " insufficient-funds rejections across 12 threads; no negative balance; supply conserved; " + l.verify();
        }
    }

    // ------------------------------------------------------------------ payroll

    static String payroll() throws Exception {
        try (Ledger l = fresh("payroll")) {
            World w = new World(l);
            w.c.setPayroll(w.O, w.acme, w.eve, 25_000, "t");      // 250.00
            w.c.setPayroll(w.D, w.acme, w.mia, 17_500, "t");      // 175.00
            check(expectError(() -> w.c.setPayroll(w.A, w.acme, w.eve, 1, "t")).equals("forbidden"), "accountant can't edit payroll");
            check(expectError(() -> w.c.setPayroll(w.O, w.acme, w.otto, 1, "t")).equals("not_member"), "only members");
            // short treasury: nobody is paid, nothing recorded, the period id is not used up
            w.c.deposit(w.O, w.acme, 30_000, "seed", "minecraft", null);
            long seq = l.seq(), eve0 = l.balance(w.eve), mia0 = l.balance(w.mia);
            check(expectError(() -> w.c.runPayroll(w.O, w.acme, "2026-W40", "minecraft")).equals("insufficient_funds"), "short");
            check(l.seq() == seq && l.balance(w.eve) == eve0 && l.balance(w.mia) == mia0 && w.bal() == 30_000, "all-or-nothing: nobody paid, no debt");
            check(!w.c.state().payrollRan(w.acme, "2026-W40"), "run id not consumed");
            check(expectError(() -> w.c.runPayroll(w.E, w.acme, "2026-W40", "minecraft")).equals("forbidden"), "employee can't run payroll");
            w.c.deposit(w.D, w.acme, 20_000, "top up", "minecraft", null);
            Ledger.Result run = w.c.runPayroll(w.O, w.acme, "2026-W40", "minecraft");
            check(!run.replayed() && run.tx().postings().size() == 3 && run.tx().type().equals("payroll_run"), "one line, three postings");
            check(l.balance(w.eve) == eve0 + 25_000 && l.balance(w.mia) == mia0 + 17_500 && w.bal() == 7_500, "paid exactly");
            // re-running the same period never pays twice, even if lines changed meanwhile or someone else runs it
            w.c.setPayroll(w.O, w.acme, w.eve, 99_999, "t");
            Ledger.Result again = w.c.runPayroll(w.D, w.acme, "2026-W40", "discord");
            check(again.replayed() && again.tx().txid().equals(run.tx().txid()) && l.balance(w.eve) == eve0 + 25_000, "same period pays once");
            // removed member is off the payroll
            w.c.setPayroll(w.O, w.acme, w.eve, 1_000, "t");
            w.c.remove(w.O, w.acme, w.mia, "t");
            check(!w.co().payroll().containsKey(w.mia), "removed member off payroll");
            Ledger.Result w41 = w.c.runPayroll(w.O, w.acme, "2026-W41", "minecraft");
            check(w41.tx().postings().size() == 2 && w.bal() == 6_500, "next period");
            check(expectError(() -> w.c.runPayroll(w.O, w.acme, "bad id with spaces", "minecraft")).equals("invalid_run_id"), "run id validated");
            w.c.setPayroll(w.O, w.acme, w.eve, 0, "t");
            check(expectError(() -> w.c.runPayroll(w.O, w.acme, "2026-W42", "minecraft")).equals("payroll_empty"), "empty");
            return "Acme -> Eve 250.00 + Mia 175.00 in ONE journal line (atomic batch); short treasury pays nobody and keeps the id reusable; "
                + "re-run of 2026-W40 returned the original tx (not paid twice); removal drops the line; " + l.verify();
        }
    }

    static String payrollConcurrent() throws Exception {
        try (Ledger l = fresh("payroll-concurrent")) {
            World w = new World(l);
            w.c.setPayroll(w.O, w.acme, w.eve, 1_000, "t");
            w.c.deposit(w.O, w.acme, 5_000, "seed", "minecraft", null);
            long eve0 = l.balance(w.eve);
            ExecutorService ex = Executors.newFixedThreadPool(8);
            List<Future<Ledger.Result>> fs = new ArrayList<>();
            for (int i = 0; i < 32; i++) fs.add(ex.submit(() -> w.c.runPayroll(i2(w), w.acme, "2026-09", "test")));
            Set<String> txids = new HashSet<>();
            int fresh = 0;
            for (Future<Ledger.Result> f : fs) { Ledger.Result r = f.get(); txids.add(r.tx().txid()); if (!r.replayed()) fresh++; }
            ex.shutdown();
            check(fresh == 1 && txids.size() == 1 && l.balance(w.eve) == eve0 + 1_000 && w.bal() == 4_000, "32 concurrent runs of one period pay once");
            return "32 concurrent runs of period 2026-09 -> 1 payment, 31 replays of txid " + txids.iterator().next().substring(0, 8) + "; " + l.verify();
        }
    }

    static Actor i2(World w) { return ThreadLocalRandom.current().nextBoolean() ? w.O : w.D; }

    // ------------------------------------------------------------------ invoices

    static String invoices() throws Exception {
        try (Ledger l = fresh("invoices")) {
            World w = new World(l);
            String bakery = Json.str(w.c.create(w.X, "Otto's Bakery", null, "minecraft", null).tx().meta(), "company");
            String farm = w.acme;   // "the farm pays the bakery": Acme owes Otto's Bakery
            w.c.deposit(w.O, farm, 10_000, "seed", "minecraft", null);
            check(expectError(() -> w.c.createInvoice(w.E, farm, bakery, 100, "x", null, "t", null)).equals("forbidden"), "employee can't invoice");
            check(expectError(() -> w.c.createInvoice(w.X, bakery, bakery, 100, "x", null, "t", null)).equals("same_account"), "self invoice");
            Ledger.Result inv = w.c.createInvoice(w.X, bakery, farm, 4_200, "40 loaves", "2026-10-06", "minecraft", null);
            String id = Json.str(inv.tx().meta(), "invoice");
            Invoice i = w.c.state().invoice(id).orElseThrow();
            check(i.number() == 1 && i.status() == InvoiceStatus.OPEN && i.issuer().equals(bakery) && i.to().equals(farm) && inv.tx().postings().isEmpty(), "open, no money yet");
            check(expectError(() -> w.c.payInvoice(w.E, id, "t")).equals("forbidden"), "employee can't pay company invoice");
            check(expectError(() -> w.c.payInvoice(w.X, id, "t")).equals("not_member"), "issuer can't pay its own invoice from the other company");
            Ledger.Result p1 = w.c.payInvoice(w.D, id, "minecraft");
            Ledger.Result p2 = w.c.payInvoice(w.O, id, "discord");
            i = w.c.state().invoice(id).orElseThrow();
            check(!p1.replayed() && p2.replayed() && p2.tx().txid().equals(p1.tx().txid()), "second payment returns the first");
            check(i.status() == InvoiceStatus.PAID && i.paidTx().equals(p1.tx().txid()) && i.paidBy().equals(farm), "linked to exact tx");
            check(p1.tx().postingFor(farm).amount() == -4_200 && p1.tx().postingFor(bakery).amount() == 4_200 && p1.tx().postings().size() == 2, "exact postings");
            check(w.bal() == 5_800 && l.accountBalance(bakery) == 4_200, "paid once");
            check(expectError(() -> w.c.voidInvoice(w.X, id, "late", "t")).equals("invoice_not_open"), "paid can't be voided");
            // to a player; void; wrong payer
            Ledger.Result inv2 = w.c.createInvoice(w.O, farm, Ledger.acct(w.otto), 900, "power bill", null, "minecraft", null);
            String id2 = Json.str(inv2.tx().meta(), "invoice");
            check(expectError(() -> w.c.payInvoice(w.E, id2, "t")).equals("forbidden"), "only the invoiced player pays");
            check(expectError(() -> w.c.voidInvoice(w.X, id2, "no", "t")).equals("not_member"), "recipient can't void");
            w.c.voidInvoice(w.Mi, id2, "billing error", "minecraft");
            check(expectError(() -> w.c.payInvoice(w.X, id2, "t")).equals("invoice_not_open"), "void can't be paid");
            Ledger.Result inv3 = w.c.createInvoice(w.A, farm, Ledger.acct(w.otto), 1_000_000_000, "huge", null, "minecraft", null);
            String id3 = Json.str(inv3.tx().meta(), "invoice");
            check(expectError(() -> w.c.payInvoice(w.X, id3, "t")).equals("insufficient_funds"), "insufficient");
            check(w.c.state().invoice(id3).orElseThrow().status() == InvoiceStatus.OPEN, "stays open");
            // concurrent double pay of one invoice
            Ledger.Result inv4 = w.c.createInvoice(w.A, farm, Ledger.acct(w.otto), 500, "concurrent", null, "minecraft", null);
            String id4 = Json.str(inv4.tx().meta(), "invoice");
            long otto0 = l.balance(w.otto);
            ExecutorService ex = Executors.newFixedThreadPool(8);
            List<Future<Ledger.Result>> fs = new ArrayList<>();
            for (int k = 0; k < 20; k++) fs.add(ex.submit(() -> w.c.payInvoice(w.X, id4, "t")));
            int freshPays = 0;
            for (Future<Ledger.Result> f : fs) if (!f.get().replayed()) freshPays++;
            ex.shutdown();
            check(freshPays == 1 && l.balance(w.otto) == otto0 - 500, "20 concurrent payments of one invoice charge once");
            check(w.c.state().invoicesFor(farm).size() == 4, "listing");
            return "invoice #1 Otto's Bakery -> Acme 42.00 paid once by tx " + p1.tx().txid().substring(0, 8)
                + " (second attempt replayed, not charged); void/paid rules; wrong payer rejected; 20 concurrent pays = 1 charge; " + l.verify();
        }
    }

    // ------------------------------------------------------------------ shops

    static int holdN = 0;

    /** One LC-style trade through the ledger exactly as LcShops does it: hold from the buyer, then settle via the registry. */
    static Ledger.Tx sale(Ledger l, UUID buyer, String trader, UUID lcOwner, long price) throws Exception {
        String hold = "hold-" + (++holdN) + "-" + UUID.randomUUID();
        l.hold(hold, buyer, price, "Shop: bread", Json.obj("trader", trader, "shop", "Bakery", "item", "1x Bread", "kind", "shop"));
        return l.settleShop(hold, trader, lcOwner, "Shop sale: 1x Bread").tx();
    }

    static String shops() throws Exception {
        try (Ledger l = fresh("shops")) {
            World w = new World(l);
            String personalTrader = "7", companyTrader = "8", eveTrader = "9";
            // personal shop still pays its owner, and the event is a plain shop_settle with no company fields
            Ledger.Tx p = sale(l, w.otto, personalTrader, w.mia, 300);
            check(p.postingFor(Ledger.acct(w.mia)).amount() == 300 && !p.meta().has("company") && p.type().equals("shop_settle"), "personal shop unchanged");
            // outsider / employee / not-the-LC-owner can't assign
            List<String> codes = new ArrayList<>();
            codes.add(expectError(() -> w.c.assignShop(w.X, w.acme, companyTrader, "Bakery", w.otto, "t")));   // outsider, owns the trader
            codes.add(expectError(() -> w.c.assignShop(w.E, w.acme, eveTrader, "Eve's stall", w.eve, "t")));    // employee owns it but lacks SHOPS
            codes.add(expectError(() -> w.c.assignShop(w.Mi, w.acme, companyTrader, "Bakery", w.dan, "t")));  // manager, but Dan owns the trader
            codes.add(expectError(() -> w.c.assignShop(w.Mi, w.acme, "not-a-number", "Bakery", w.mia, "t")));
            check(codes.equals(List.of("not_member", "forbidden", "forbidden", "bad_trader")), "codes " + codes);
            // manager assigns the trader she owns: revenue now goes to the company treasury
            w.c.assignShop(w.Mi, w.acme, companyTrader, "Acme Store", w.mia, "minecraft");
            long mia0 = l.balance(w.mia);
            Ledger.Tx cs = sale(l, w.otto, companyTrader, w.mia, 450);
            check(w.bal() == 450 && l.balance(w.mia) == mia0 && cs.postingFor(w.acme).amount() == 450, "company shop pays the company");
            check(w.acme.equals(Json.str(cs.meta(), "company")) && Json.str(cs.meta(), "company_route_skipped") == null, "routed and audited");
            check(expectError(() -> w.c.assignShop(w.D, Json.str(w.c.create(w.D, "Dan Co", null, "t", null).tx().meta(), "company"), companyTrader, "x", w.mia, "t"))
                .equals("forbidden"), "can't steal an assigned shop (not the LC owner)");
            // employee can't redirect: can't unassign, can't reassign
            check(expectError(() -> w.c.unassignShop(w.E, companyTrader, null, "t")).equals("forbidden"), "employee can't unassign");
            check(expectError(() -> w.c.unassignShop(w.X, companyTrader, null, "t")).equals("not_member"), "outsider can't unassign");
            // LC ownership moved to someone else (e.g. /lcadmin or trade): falls back to the current owner, audited
            Ledger.Tx moved = sale(l, w.otto, companyTrader, w.dan, 100);
            check(moved.postingFor(Ledger.acct(w.dan)).amount() == 100 && "trader_owner_changed".equals(Json.str(moved.meta(), "company_route_skipped")), "owner change -> personal");
            // shop operator leaves: assignment ends in the same journal line, revenue goes back to her personally
            w.c.leave(w.Mi, w.acme, "t");
            check(w.c.state().shop(companyTrader).isEmpty(), "unassigned on leave");
            Ledger.Tx after = sale(l, w.otto, companyTrader, w.mia, 200);
            check(after.postingFor(Ledger.acct(w.mia)).amount() == 200 && !after.meta().has("company"), "back to personal");
            // director re-assigns her own shop; the director (SHOPS) can unassign one another member operates
            w.c.assignShop(w.D, w.acme, "10", "Dan's Depot", w.dan, "t");
            w.join(w.mia, Role.MANAGER);
            w.c.assignShop(w.Mi, w.acme, companyTrader, "Acme Store", w.mia, "t");
            w.c.unassignShop(w.D, companyTrader, "store closed", "t");
            w.c.assignShop(w.Mi, w.acme, companyTrader, "Acme Store", w.mia, "t");
            w.c.unassignShop(w.Mi, companyTrader, null, "t");   // operator may always take it back
            check(w.c.state().shopsOf(w.acme).size() == 1, "one shop left");
            // duplicate settlement of one trade pays once
            String hold = "dup-hold";
            l.hold(hold, w.otto, 50, "Shop", Json.obj("trader", "10", "item", "x"));
            Ledger.Result s1 = l.settleShop(hold, "10", w.dan, "sale"), s2 = l.settleShop(hold, "10", w.dan, "sale");
            check(!s1.replayed() && s2.replayed() && s1.tx().txid().equals(s2.tx().txid()), "duplicate settle pays once");
            return "personal shop pays owner (unchanged); outsider/employee/non-owner assign rejected " + codes + "; manager's shop pays Acme; "
                + "LC owner change and operator leaving fall back to personal (audited); duplicate settle idempotent; " + l.verify();
        }
    }

    static String shopConcurrency() throws Exception {
        Path p = path("shop-concurrency");
        toVerify.add(p);
        long expectCompany;
        String acme, digest;
        UUID mia;
        try (Ledger l = new Ledger(p, 0)) {
            World w = new World(l);
            acme = w.acme;
            mia = w.mia;
            w.c.assignShop(w.Mi, w.acme, "100", "Acme Store", w.mia, "t");
            UUID[] buyers = {w.otto, w.eve, w.abe, w.dan};
            ExecutorService ex = Executors.newFixedThreadPool(8);
            List<Future<Long>> fs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                final int seed = t;
                fs.add(ex.submit(() -> {
                    Random r = new Random(seed);
                    long got = 0;
                    for (int i = 0; i < 150; i++) {
                        boolean company = r.nextBoolean();
                        long price = 1 + r.nextInt(200);
                        try {
                            Ledger.Tx tx = sale(l, buyers[r.nextInt(buyers.length)], company ? "100" : "101", w.mia, price);
                            if (company) got += tx.postingFor(w.acme).amount();
                        } catch (Ledger.LedgerError e) {
                            if (!e.code.equals("insufficient_funds")) throw e;
                        }
                    }
                    return got;
                }));
            }
            expectCompany = 0;
            for (Future<Long> f : fs) expectCompany += f.get();
            ex.shutdown();
            check(w.bal() == expectCompany && l.accountBalance(Ledger.ESCROW) == 0 && l.openHolds().isEmpty(), "every sale settled to the right place");
            digest = l.companies().digest();
        }
        try (Ledger l = new Ledger(p, 0)) {
            check(l.accountBalance(acme) == expectCompany && l.companies().digest().equals(digest), "restart: same treasury and registry");
            check(l.companies().shop("100").orElseThrow().operator().equals(mia), "assignment persisted");
            Ledger.Tx after = sale(l, u("otto"), "100", mia, 10);
            check(after.postingFor(acme).amount() == 10, "routing works after restart");
            return "8 threads x 150 mixed company/personal sales: company received " + M.format(expectCompany + 10) + " (incl. 1 after restart), escrow 0; registry identical after restart; " + l.verify();
        }
    }

    // ------------------------------------------------------------------ dissolution

    static String dissolution() throws Exception {
        try (Ledger l = fresh("dissolution")) {
            World w = new World(l);
            String rail = Json.str(w.c.create(w.X, "Otto Rail", null, "minecraft", null).tx().meta(), "company");
            w.c.deposit(w.O, w.acme, 6_000, "seed", "minecraft", null);
            w.c.assignShop(w.Mi, w.acme, "55", "Acme Store", w.mia, "t");
            w.c.setPayroll(w.O, w.acme, w.eve, 100, "t");
            String issued = Json.str(w.c.createInvoice(w.O, w.acme, rail, 700, "power", null, "t", null).tx().meta(), "invoice");
            String owed = Json.str(w.c.createInvoice(w.X, rail, w.acme, 300, "freight", null, "t", null).tx().meta(), "invoice");
            long seq = l.seq();
            check(expectError(() -> w.c.dissolve(w.O, w.acme, null, "done", "t", null)).equals("balance_not_zero"), "money must go somewhere explicit");
            check(expectError(() -> w.c.dissolve(w.D, w.acme, Ledger.acct(w.dan), "x", "t", null)).equals("forbidden"), "director can't dissolve");
            check(expectError(() -> w.c.dissolve(w.O, w.acme, w.acme, "x", "t", null)).equals("same_account"), "not to itself");
            check(l.seq() == seq, "rejections commit nothing");
            long olive0 = l.balance(w.olive);
            Ledger.Result d = w.c.dissolve(w.O, w.acme, Ledger.acct(w.olive), "closing down", "minecraft", "cmd:dissolve-1");
            Ledger.Result d2 = w.c.dissolve(w.O, w.acme, Ledger.acct(w.olive), "closing down", "minecraft", "cmd:dissolve-1");
            check(d2.replayed() && d2.tx().txid().equals(d.tx().txid()), "double-confirm replays");
            Company acme = w.co();
            check(acme.status() == Status.DISSOLVED && w.bal() == 0 && l.balance(w.olive) == olive0 + 6_000, "payout explicit and exact");
            check(w.c.state().shopsOf(w.acme).isEmpty() && acme.payroll().isEmpty() && acme.invites().isEmpty(), "shops/payroll/invites cleared");
            check(w.c.state().invoice(issued).orElseThrow().status() == InvoiceStatus.VOID && w.c.state().invoice(owed).orElseThrow().status() == InvoiceStatus.VOID, "open invoices voided");
            // a shop that pointed at it pays its owner again; nothing can reach the dead treasury
            Ledger.Tx s = sale(l, w.otto, "55", w.mia, 90);
            check(s.postingFor(Ledger.acct(w.mia)).amount() == 90 && w.bal() == 0, "dead company's shop pays owner");
            List<String> codes = new ArrayList<>();
            codes.add(expectError(() -> w.c.deposit(w.E, w.acme, 1, "x", "t", null)));
            codes.add(expectError(() -> w.c.pay(w.X, rail, w.acme, 1, "x", "t", null)));
            codes.add(expectError(() -> w.c.assignShop(w.Mi, w.acme, "56", "x", w.mia, "t")));
            codes.add(expectError(() -> w.c.invite(w.O, w.acme, w.otto, Role.EMPLOYEE, "t")));
            codes.add(expectError(() -> w.c.runPayroll(w.O, w.acme, "late", "t")));
            codes.add(expectError(() -> w.c.create(w.O, "acme power", null, "t", null)));
            check(codes.equals(List.of("company_inactive", "company_inactive", "company_inactive", "company_inactive", "company_inactive", "name_taken")), "codes " + codes);
            // zero-balance dissolution needs no payout; payout to another company works
            String empty = Json.str(w.c.create(w.E, "Tiny Co", null, "t", null).tx().meta(), "company");
            w.c.dissolve(w.E, empty, null, "never used", "t", null);
            String funded = Json.str(w.c.create(w.E, "Funded Co", null, "t", null).tx().meta(), "company");
            w.c.deposit(w.E, funded, 400, "x", "t", null);
            w.c.dissolve(Actor.staff("console:ops", null), funded, rail, "staff: abandoned", "admin", null);
            check(l.accountBalance(rail) == 400 && l.accountBalance(funded) == 0, "staff dissolution with explicit payout to a company");
            return "nonzero balance needs an explicit payout (rejected otherwise); payout " + M.format(6_000) + " to owner in the same line that "
                + "unassigned 1 shop and voided 2 invoices; later ops rejected " + codes + "; name stays reserved; " + l.verify();
        }
    }

    // ------------------------------------------------------------------ safety

    static String rejectsChangeNothing() throws Exception {
        try (Ledger l = fresh("rejects")) {
            World w = new World(l);
            w.c.deposit(w.O, w.acme, 1_000, "seed", "minecraft", null);
            w.c.assignShop(w.O, w.acme, "5", "Store", w.olive, "t");
            String inv = Json.str(w.c.createInvoice(w.O, w.acme, Ledger.acct(w.otto), 50, "x", null, "t", null).tx().meta(), "invoice");
            w.c.payInvoice(w.X, inv, "t");
            String before = snapshot(l);
            Actor forged = new Actor("mc:" + w.olive, u("mallory"), false);
            Actor console = new Actor("console:x", null, false);
            List<T> bad = List.of(
                () -> w.c.pay(forged, w.acme, Ledger.acct(w.otto), 1, "x", "t", null),
                () -> w.c.pay(w.E, w.acme, Ledger.acct(w.eve), 1, "x", "t", null),
                () -> w.c.pay(w.O, "company:" + UUID.randomUUID(), Ledger.acct(w.eve), 1, "x", "t", null),
                () -> w.c.pay(w.O, w.acme, Ledger.acct(w.eve), 100_001, "x", "t", null),
                () -> w.c.setRole(w.D, w.acme, w.dan, Role.OWNER, "t"),
                () -> w.c.remove(w.E, w.acme, w.olive, "t"),
                () -> w.c.leave(w.O, w.acme, "t"),
                () -> w.c.setRole(w.O, w.acme, w.olive, Role.EMPLOYEE, "t"),
                () -> w.c.create(w.X, "ACME POWER", null, "t", null),
                () -> w.c.rename(w.O, w.acme, "a", "t"),
                () -> w.c.payInvoice(w.X, inv, "t"),   // replay: returns original, commits nothing
                () -> w.c.voidInvoice(w.O, inv, "x", "t"),
                () -> w.c.dissolve(w.O, w.acme, null, "x", "t", null),
                () -> w.c.assignShop(w.X, w.acme, "6", "x", w.otto, "t"),
                () -> w.c.assignShop(w.O, w.acme, "5", "x", w.olive, "t"),
                () -> w.c.unassignShop(w.E, "5", null, "t"),
                () -> w.c.runPayroll(w.O, w.acme, "p1", "t"),
                () -> w.c.grantPay(w.O, w.acme, w.eve, true, "t"),
                () -> w.c.setSuspended(w.O, w.acme, true, "x", "t"),
                () -> w.c.staffAddOwner(w.O, w.acme, w.otto, "x", "t"),
                () -> w.c.create(console, "Ghost Co", null, "t", null),
                () -> w.c.deposit(console, w.acme, 1, "x", "t", null));
            int n = 0;
            for (T t : bad) {
                try { t.run(); } catch (Ledger.LedgerError e) { n++; }
                check(snapshot(l).equals(before), "state changed after bad action #" + n);
            }
            check(n == bad.size() - 1, "all but the replay threw: " + n);
            return bad.size() + " forged/unauthorised/invalid actions (forged actor, forged company id, employee withdrawal, final owner, name collision, "
                + "replayed invoice payment, ...): journal seq, every balance and the company digest unchanged after each";
        }
    }

    static String restart() throws Exception {
        Path p = path("restart");
        toVerify.add(p);
        String digest, invoice, acme;
        Map<String, Long> balances;
        long seq;
        String payTx;
        try (Ledger l = new Ledger(p, 0)) {
            World w = new World(l);
            acme = w.acme;
            w.c.deposit(w.O, w.acme, 9_000, "seed", "minecraft", null);
            payTx = w.c.pay(w.O, w.acme, Ledger.acct(w.eve), 100, "x", "discord", "restart-pay-0001").tx().txid();
            w.c.setPayroll(w.O, w.acme, w.eve, 300, "t");
            w.c.runPayroll(w.O, w.acme, "2026-W40", "t");
            w.c.assignShop(w.Mi, w.acme, "77", "Acme Store", w.mia, "t");
            invoice = Json.str(w.c.createInvoice(w.O, w.acme, Ledger.acct(w.otto), 500, "x", null, "t", null).tx().meta(), "invoice");
            w.c.grantPay(w.O, w.acme, w.abe, true, "t");
            w.c.rename(w.O, w.acme, "Acme Power & Light", "t");
            digest = l.companies().digest();
            balances = l.balancesSnapshot();
            seq = l.seq();
        }
        try (Ledger l = new Ledger(p, 0)) {
            CompanyLedger c = new CompanyLedger(l);
            check(l.companies().digest().equals(digest) && l.balancesSnapshot().equals(balances) && l.seq() == seq, "identical state after replay");
            check(c.pay(Actor.player(u("olive")), acme, Ledger.acct(u("eve")), 100, "x", "discord", "restart-pay-0001").tx().txid().equals(payTx), "idempotency survives restart");
            check(c.runPayroll(Actor.player(u("olive")), acme, "2026-W40", "t").replayed(), "payroll run id survives restart");
            check(c.state().byName("ACME POWER & LIGHT").isPresent() && c.state().byName("Acme Power").isEmpty(), "rename survives");
            check(c.state().get(acme).orElseThrow().members().get(u("abe")).payGrant(), "grant survives");
            c.payInvoice(Actor.player(u("otto")), invoice, "t");
            check(c.state().invoice(invoice).orElseThrow().status() == InvoiceStatus.PAID, "invoice still payable after restart");
            return "seq " + seq + ": balances + company digest " + digest.substring(0, 12) + " identical after reopen; idempotency, payroll ids, names, grants, invoices survive; " + l.verify();
        }
    }

    static String torn() throws Exception {
        Path p = path("torn");
        toVerify.add(p);
        String digest, acme;
        try (Ledger l = new Ledger(p, 0)) {
            World w = new World(l);
            acme = w.acme;
            w.c.deposit(w.O, w.acme, 1_000, "seed", "minecraft", null);
            digest = l.companies().digest();
        }
        Files.writeString(p, "{\"seq\":99,\"prev\":\"x\",\"type\":\"company_member\",\"meta\":{\"company\":\"" + acme + "\",\"op\":\"leave\"", StandardOpenOption.APPEND);
        try (Ledger l = new Ledger(p, 0)) {
            check(l.tornNote() != null && l.companies().digest().equals(digest), "torn company event dropped, state is last acknowledged");
            new CompanyLedger(l).pay(Actor.player(u("olive")), acme, Ledger.acct(u("otto")), 1, "after", "t", null);
        }
        try (Ledger l = new Ledger(p, 0)) {
            check(l.accountBalance(acme) == 999, "chain continues");
            String side;
            try (var st = Files.list(p.getParent())) { side = st.map(x -> x.getFileName().toString()).filter(x -> x.contains(".torn-")).findFirst().orElse("none"); }
            check(!side.equals("none"), "torn bytes kept in a side file");
            return "torn company event moved to " + side + ", company state = last acknowledged, chain continues; " + l.verify();
        }
    }

    static String tamper() throws Exception {
        Path p = path("tamper");
        try (Ledger l = new Ledger(p, 0)) {
            World w = new World(l);
            w.c.deposit(w.O, w.acme, 1_000, "seed", "minecraft", null);
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(p));
        int i = -1;
        for (int k = 0; k < lines.size(); k++) if (lines.get(k).contains("\"op\":\"join\"") && lines.get(k).contains("EMPLOYEE")) i = k;
        lines.set(i, lines.get(i).replace("EMPLOYEE", "OWNER"));   // try to promote Eve by editing the file
        Files.writeString(p, String.join("\n", lines) + "\n");
        try (Ledger l = new Ledger(p, 0)) {
            throw new AssertionError("tampered journal opened");
        } catch (Journal.CorruptJournalException e) {
            return "editing a membership event (EMPLOYEE->OWNER) breaks the hash chain: " + e.getMessage();
        }
    }

    static String hardKill() throws Exception {
        Path p = path("crash");
        toVerify.add(p);
        String java = ProcessHandle.current().info().command().orElse("java");
        String cp = System.getProperty("java.class.path");
        List<String> notes = new ArrayList<>();
        Random r = new Random(11);
        for (int round = 0; round < 4; round++) {
            Process child = new ProcessBuilder(java, "-cp", cp, "CompanyTest", "--crash-child", p.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Thread.sleep(900 + r.nextInt(900));
            child.destroyForcibly().waitFor();
            try (Ledger l = new Ledger(p, 0)) {
                l.verify();
                for (Map.Entry<String, Long> e : l.balancesSnapshot().entrySet()) if (!e.getKey().equals(Ledger.TREASURY)) check(e.getValue() >= 0, "negative " + e);
                for (Company c : l.companies().all()) check(c.owners() >= 1, "ownerless company");
                long paid = 0;
                for (Company c : l.companies().all())
                    for (Invoice i : l.companies().invoicesFor(c.id())) if (i.status() == InvoiceStatus.PAID) { paid++; check(i.paidTx() != null, "paid invoice without tx"); }
                notes.add("kill " + round + ": seq=" + l.seq() + " companies=" + l.companies().all().size() + " paid_invoices=" + paid
                    + (l.tornNote() != null ? " torn-tail-truncated" : "") + " verify ok");
            }
        }
        return String.join("; ", notes);
    }

    static void crashChild(Path p) throws Exception {
        try (Ledger l = new Ledger(p, 0)) {
            UUID boss = u("k-boss"), worker = u("k-worker"), client = u("k-client");
            l.ensureAccount(boss, "Boss", "test"); l.ensureAccount(worker, "Worker", "test"); l.ensureAccount(client, "Client", "test");
            if (l.balance(boss) == 0) { l.adjust(boss, 1_000_000_000, "seed", "admin", "console", null); l.adjust(client, 1_000_000_000, "seed", "admin", "console", null); }
            CompanyLedger c = new CompanyLedger(l);
            Actor B = Actor.player(boss), C = Actor.player(client);
            String co = c.state().byName("Crash Co").map(Company::id).orElse(null);
            if (co == null) {
                co = Json.str(c.create(B, "Crash Co", null, "t", null).tx().meta(), "company");
                c.invite(B, co, worker, Role.EMPLOYEE, "t");
                c.answerInvite(Actor.player(worker), co, true, "t");
                c.setPayroll(B, co, worker, 7, "t");
                c.assignShop(B, co, "900", "Crash Store", boss, "t");
            }
            Random r = new Random();
            for (long n = 0; ; n++) {
                try {
                    switch (r.nextInt(5)) {
                        case 0 -> c.deposit(B, co, 1 + r.nextInt(500), "x", "t", null);
                        case 1 -> c.pay(B, co, Ledger.acct(client), 1 + r.nextInt(200), "x", "t", null);
                        case 2 -> c.runPayroll(B, co, "run-" + System.nanoTime(), "t");
                        case 3 -> c.payInvoice(C, Json.str(c.createInvoice(B, co, Ledger.acct(client), 1 + r.nextInt(100), "x", null, "t", null).tx().meta(), "invoice"), "t");
                        default -> sale(l, client, "900", boss, 1 + r.nextInt(50));
                    }
                } catch (Ledger.LedgerError ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------ bridge

    static final byte[] SECRET = "lab-only-secret-0123456789abcdef-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    static final HttpClient HTTP = HttpClient.newHttpClient();

    record Resp(int status, JsonObject body) {
        String error() { return body.has("error") ? body.get("error").getAsString() : null; }
    }

    static Resp call(int port, String path, JsonObject body) throws Exception {
        String json = Json.GSON.toJson(body);
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String canonical = "POST\n" + path + "\n" + ts + "\n" + nonce + "\n"
            + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).POST(HttpRequest.BodyPublishers.ofString(json))
            .header("X-Indigo-Timestamp", ts).header("X-Indigo-Nonce", nonce)
            .header("X-Indigo-Signature", HexFormat.of().formatHex(m.doFinal(canonical.getBytes(StandardCharsets.UTF_8)))).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), JsonParser.parseString(r.body()).getAsJsonObject());
    }

    static String bridge() throws Exception {
        try (Ledger l = fresh("bridge")) {
            World w = new World(l);
            Links links = new Links(l, System::currentTimeMillis, 300_000, 5, 10, 600_000);
            List<String> events = new ArrayList<>();
            Bridge.Events ev = new Bridge.Events() {
                public void transfer(Ledger.Tx tx, UUID from, UUID to) { }
                public void linked(UUID uuid, boolean linked) { }
                public void companyPayment(Ledger.Tx tx) { events.add(tx.txid()); }
            };
            String dOlive = "200000000000000001", dEve = "200000000000000005", dOtto = "200000000000000006", dStranger = "200000000000000009";
            l.link(w.olive, dOlive, "test", "test");
            l.link(w.eve, dEve, "test", "test");
            l.link(w.otto, dOtto, "test", "test");
            w.c.deposit(w.O, w.acme, 10_000, "seed", "minecraft", null);
            String rail = Json.str(w.c.create(w.X, "Otto Rail", null, "t", null).tx().meta(), "company");
            try (Bridge br = new Bridge(l, links, M, SECRET, ev, System::currentTimeMillis, "127.0.0.1", 0, 1000, Runnable::run)) {
                int port = br.port();
                List<String> notes = new ArrayList<>();
                check(call(port, "/v1/company/list", Json.obj("discord_user_id", dOlive)).error().equals("disabled"), "off by default");
                br.companies(true);
                Resp list = call(port, "/v1/company/list", Json.obj("discord_user_id", dOlive));
                check(list.status() == 200 && list.body().getAsJsonArray("companies").size() == 1, "list " + list.body());
                check(call(port, "/v1/company/list", Json.obj("discord_user_id", dStranger)).error().equals("not_linked"), "unlinked user");
                Resp info = call(port, "/v1/company/info", Json.obj("discord_user_id", dEve));   // only company -> implicit
                check(info.status() == 200 && info.body().get("company_name").getAsString().equals("Acme Power") && info.body().get("role").getAsString().equals("Employee"), "info " + info.body());
                check(!info.body().has("balance_minor"), "info never leaks the balance");
                Resp mem = call(port, "/v1/company/members", Json.obj("discord_user_id", dEve, "company", "acme power"));
                check(mem.status() == 200 && mem.body().getAsJsonArray("members").size() == 5, "members");
                // linked but no finance role / not a member
                check(call(port, "/v1/company/balance", Json.obj("discord_user_id", dEve, "company", "Acme Power")).error().equals("forbidden"), "employee: no balance");
                check(call(port, "/v1/company/transactions", Json.obj("discord_user_id", dEve, "company", "Acme Power")).error().equals("forbidden"), "employee: no transactions");
                check(call(port, "/v1/company/invoices", Json.obj("discord_user_id", dEve, "company", "Acme Power")).error().equals("forbidden"), "employee: no invoices");
                check(call(port, "/v1/company/balance", Json.obj("discord_user_id", dOtto, "company", "Acme Power")).error().equals("not_member"), "outsider");
                check(call(port, "/v1/company/balance", Json.obj("discord_user_id", dOtto, "company", w.acme)).error().equals("not_member"), "outsider by id");
                notes.add("disabled->503; unlinked->not_linked; employee->forbidden for balance/transactions/invoices; outsider->not_member");
                Resp bal = call(port, "/v1/company/balance", Json.obj("discord_user_id", dOlive, "company", "Acme Power"));
                check(bal.body().get("balance_minor").getAsLong() == 10_000, "balance");
                // pay: employee refused server-side, owner pays player then company, replay + conflict
                JsonObject pay = Json.obj("discord_user_id", dEve, "company", "Acme Power", "to_discord_user_id", dOtto, "amount", "5", "idempotency_key", "discord-cpay-aaaa0001");
                check(call(port, "/v1/company/pay", pay).error().equals("forbidden") && w.bal() == 10_000, "employee can't pay via Discord");
                pay.addProperty("discord_user_id", dOlive);
                Resp p1 = call(port, "/v1/company/pay", pay), p2 = call(port, "/v1/company/pay", pay);
                check(p1.status() == 200 && !p1.body().get("replayed").getAsBoolean() && p2.body().get("replayed").getAsBoolean()
                    && p1.body().get("txid").getAsString().equals(p2.body().get("txid").getAsString()) && w.bal() == 9_500, "pay once " + p1.body());
                pay.addProperty("amount", "6");
                check(call(port, "/v1/company/pay", pay).error().equals("idempotency_conflict"), "conflict");
                Resp pc = call(port, "/v1/company/pay", Json.obj("discord_user_id", dOlive, "to_company", "otto rail", "amount", "10", "idempotency_key", "discord-cpay-aaaa0002"));
                check(pc.status() == 200 && l.accountBalance(rail) == 1_000 && pc.body().get("to_name").getAsString().equals("Otto Rail"), "company -> company " + pc.body());
                check(call(port, "/v1/company/pay", Json.obj("discord_user_id", dOlive, "to_company", "otto rail", "to_discord_user_id", dOtto, "amount", "1", "idempotency_key", "discord-cpay-aaaa0003")).status() == 400, "exactly one recipient");
                check(call(port, "/v1/company/pay", Json.obj("discord_user_id", dOlive, "to_company", "otto rail", "amount", "1")).status() == 400, "key required");
                check(call(port, "/v1/company/pay", Json.obj("discord_user_id", dOlive, "to_company", "otto rail", "amount", "99999", "idempotency_key", "discord-cpay-aaaa0004")).error().equals("insufficient_funds"), "insufficient");
                check(events.size() == 2, "in-game notices for fresh payments only");
                Resp tx = call(port, "/v1/company/transactions", Json.obj("discord_user_id", dOlive, "company", "Acme Power", "limit", 5));
                JsonArray rows = tx.body().getAsJsonArray("transactions");
                check(rows.get(0).getAsJsonObject().get("amount_display").getAsString().equals("-10.00")
                    && rows.get(0).getAsJsonObject().get("counterparty").getAsString().equals("Otto Rail"), "tx rows " + rows);
                w.c.createInvoice(w.O, w.acme, rail, 123, "power", null, "t", null);
                Resp inv = call(port, "/v1/company/invoices", Json.obj("discord_user_id", dOlive, "company", "Acme Power"));
                check(inv.body().getAsJsonArray("invoices").get(0).getAsJsonObject().get("status").getAsString().equals("open"), "invoices");
                // the personal wallet shows the company by name
                Resp mine = call(port, "/v1/transactions", Json.obj("discord_user_id", dOtto, "limit", 5));
                check(mine.body().getAsJsonArray("transactions").get(0).getAsJsonObject().get("counterparty").getAsString().equals("Acme Power"), "personal row names company");
                // several companies -> must name one
                w.c.invite(w.X, rail, w.olive, Role.DIRECTOR, "t");
                w.c.answerInvite(w.O, rail, true, "t");
                check(call(port, "/v1/company/balance", Json.obj("discord_user_id", dOlive)).error().equals("company_required"), "ambiguous");
                notes.add("owner pay 5.00 once (replay same txid, conflict 409), company->company, validation 400s, insufficient 409, ledger-authoritative balance/tx/invoices");
                return String.join("; ", notes) + "; " + l.verify();
            }
        }
    }

    // ------------------------------------------------------------------ chat console

    static String console() throws Exception {
        try (Ledger l = fresh("console")) {
            World w = new World(l);
            long[] now = {1_000_000};
            Map<UUID, CompanyConsole.ShopRef> looking = new HashMap<>();
            CompanyConsole con = new CompanyConsole(l, M, () -> now[0], p -> Optional.ofNullable(looking.get(p)));
            List<String> notes = new ArrayList<>();
            CompanyConsole.Reply r = con.run(w.X, "create \"Otto Mining\" Ore and stone");
            check(r.ok() && r.lines().get(0).contains("Founded Otto Mining"), "create " + r);
            r = con.run(w.X, "invite \"Otto Mining\" Eve employee");
            check(r.ok(), "invite " + r);
            check(con.run(w.E, "accept Otto Mining").ok(), "accept multi-word unquoted");
            r = con.run(w.E, "balance \"Otto Mining\"");
            check(!r.ok() && r.lines().get(0).contains("Employee"), "employee blocked " + r);
            r = con.run(w.X, "deposit \"Otto Mining\" 50");
            check(r.ok() && r.confirm() != null && l.accountBalance(w.c.state().byName("Otto Mining").orElseThrow().id()) == 0, "deposit asks first");
            String token = r.confirm().substring("/company confirm ".length());
            check(!con.run(w.E, "confirm " + token).ok(), "someone else's token is refused");
            CompanyConsole.Reply c1 = con.run(w.X, "confirm " + token), c2 = con.run(w.X, "confirm " + token);
            String om = w.c.state().byName("Otto Mining").orElseThrow().id();
            check(c1.ok() && c2.ok() && c2.lines().get(0).startsWith("Already done") && l.accountBalance(om) == 5_000, "double confirm = one deposit");
            r = con.run(w.X, "pay \"Otto Mining\" player Eve 12.50 ore bonus");
            CompanyConsole.Reply paid = con.run(w.X, r.confirm().substring(1).replace("company ", ""));
            check(paid.ok() && paid.notices().size() == 1 && paid.notices().get(0).player().equals(w.eve) && l.accountBalance(om) == 3_750, "pay + notice " + paid);
            r = con.run(w.X, "payroll \"Otto Mining\" set Eve 10");
            check(r.ok(), "payroll set " + r);
            r = con.run(w.X, "payroll \"Otto Mining\" run 2026-W40");
            CompanyConsole.Reply run = con.run(w.X, r.confirm().substring(1).replace("company ", ""));
            check(run.ok() && run.lines().get(0).contains("1 employee"), "run " + run);
            check(!con.run(w.X, "payroll \"Otto Mining\" run 2026-W40").ok(), "same period refused before even asking");
            r = con.run(w.X, "invoice \"Otto Mining\" company \"Acme Power\" 20 gravel");
            check(r.ok() && r.lines().get(0).startsWith("Invoice #1"), "invoice " + r);
            w.c.deposit(w.O, w.acme, 5_000, "seed", "t", null);
            r = con.run(w.O, "invoice pay 1");
            CompanyConsole.Reply ip = con.run(w.O, r.confirm().substring(1).replace("company ", ""));
            check(ip.ok() && ip.lines().get(0).contains("paid"), "invoice paid " + ip);
            check(con.run(w.O, "invoices \"Acme Power\"").lines().get(1).contains("paid"), "listing shows paid");
            // shops use the trader the player is looking at, read server-side
            check(!con.run(w.X, "shop assign Otto Mining").ok(), "not looking at a trader");
            looking.put(w.otto, new CompanyConsole.ShopRef("321", "Ore Stall", w.otto));
            check(con.run(w.X, "shop assign Otto Mining").ok() && w.c.state().shop("321").isPresent(), "assign");
            looking.put(w.eve, new CompanyConsole.ShopRef("321", "Ore Stall", w.otto));
            check(!con.run(w.E, "shop unassign").ok(), "employee can't unassign");
            check(con.run(w.E, "shop info").lines().get(0).contains("pays Otto Mining"), "info");
            // an admin/creative trader (LcShops burns its sales, so LcCompanyShops reports no owner) is never assignable
            looking.put(w.otto, new CompanyConsole.ShopRef("322", "Admin Stall", null));
            CompanyConsole.Reply adm = con.run(w.X, "shop assign Otto Mining");
            check(!adm.ok() && adm.lines().get(0).contains("admin shop") && w.c.state().shop("322").isEmpty(), "admin trader refused " + adm.lines());
            check(con.run(w.X, "shop info").lines().get(0).contains("admin shop"), "admin shop info");
            looking.put(w.otto, new CompanyConsole.ShopRef("321", "Ore Stall", w.otto));
            // expiry, help, errors
            r = con.run(w.X, "deposit \"Otto Mining\" 1");
            now[0] += 61_000;
            check(!con.run(w.X, r.confirm().substring(1).replace("company ", "")).ok(), "expired token");
            check(con.run(w.X, "help").ok() && !con.run(w.X, "nonsense").ok() && !con.run(w.X, "pay").ok(), "help/usage");
            check(!con.run(w.O, "admin suspend \"Otto Mining\" test").ok(), "admin needs staff");
            check(con.run(Actor.staff("staff:" + w.olive, w.olive), "admin suspend \"Otto Mining\" audit").ok(), "staff suspend");
            check(con.run(w.X, "info Otto Mining").lines().get(0).contains("[suspended]"), "status shown");
            List<String> h = con.run(w.X, "history Otto Mining").lines();
            check(h.size() > 8, "history " + h);
            notes.add("create/invite/accept/deposit/pay/payroll/invoice/shop/admin via text; confirm token = idempotency key (double confirm once, other player refused, expiry)");
            return String.join("; ", notes) + "; " + l.verify();
        }
    }

    // ------------------------------------------------------------------ migration

    /** Journal lines with the per-run fields (txid, ts, prev, hash, fp is deterministic) removed. */
    static List<JsonObject> normalized(Path p) throws Exception {
        List<JsonObject> out = new ArrayList<>();
        for (String s : Files.readAllLines(p)) {
            JsonObject o = JsonParser.parseString(s).getAsJsonObject();
            for (String k : List.of("txid", "ts", "prev", "hash")) o.remove(k);
            out.add(o);
        }
        return out;
    }

    static String migration() throws Exception {
        if (legacy == null) throw new AssertionError("no legacy journal given (run through scripts/economy-build.py company-test)");
        // 1. a journal written by the pre-company code (main) opens unchanged with the new code
        Path copy = path("migration");
        Files.copy(legacy, copy, StandardCopyOption.REPLACE_EXISTING);
        toVerify.add(copy);
        UUID alice = LegacyJournalGen.u("alice"), shopkeeper = LegacyJournalGen.u("shopkeeper"), bob = LegacyJournalGen.u("bob");
        Map<String, Long> legacyBalances;
        try (Ledger l = new Ledger(copy, 0)) {
            legacyBalances = l.balancesSnapshot();
            check(l.companies().all().isEmpty() && l.openHolds().size() == 1 && l.uuidForDiscord("300000000000000003").orElseThrow().equals(bob), "legacy state");
            check(l.balance(shopkeeper) == 300 + 600 + 900, "legacy shop revenue");
            l.verify();
        }
        // 2. companies can be added on top, without touching existing balances or links
        try (Ledger l = new Ledger(copy, 0)) {
            check(l.balancesSnapshot().equals(legacyBalances), "reopen identical");
            CompanyLedger c = new CompanyLedger(l);
            String bakery = Json.str(c.create(Actor.player(shopkeeper), "Pine River Bakery", null, "minecraft", null).tx().meta(), "company");
            c.assignShop(Actor.player(shopkeeper), bakery, "42", "Bakery", shopkeeper, "minecraft");
            l.hold("post-migration", alice, 1_000, "Shop", Json.obj("trader", "42", "item", "1x Cake"));
            l.settleShop("post-migration", "42", shopkeeper, "Shop sale: 1x Cake");
            check(l.accountBalance(bakery) == 1_000 && l.balance(shopkeeper) == 1_800, "same trader now pays the company");
            for (Map.Entry<String, Long> e : legacyBalances.entrySet())
                if (!e.getKey().equals(Ledger.acct(alice)) && !e.getKey().equals(bakery)) check(l.balancesSnapshot().get(e.getKey()).equals(e.getValue()), "untouched " + e.getKey());
            l.verify();
        }
        // 3. the same legacy sequence through the NEW company-aware settlement writes the same events (personal shops unchanged)
        Path again = path("migration-new-code");
        toVerify.add(again);
        try (Ledger l = new Ledger(again, 0)) {
            LegacyJournalGen.sequence(l, (lg, h, o, r) -> lg.settleShop(h, "42", o, r));
        }
        List<JsonObject> a = normalized(legacy), b = normalized(again);
        check(a.equals(b), "event-for-event identical to the pre-company code");
        return "pre-company journal (" + a.size() + " events written by main's core) opens unchanged with the new code; a company and a shop assignment "
            + "were added on top without changing any existing balance or link; the legacy sequence through settleShop() is event-for-event identical "
            + "(minus txid/ts/hash) to main's settle(), so personal shops are unchanged";
    }
}
