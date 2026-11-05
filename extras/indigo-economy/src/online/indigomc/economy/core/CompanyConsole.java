package online.indigomc.economy.core;

import online.indigomc.economy.core.Companies.*;
import online.indigomc.economy.core.CompanyLedger.Actor;
import online.indigomc.economy.core.Ledger.LedgerError;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * The {@code /company ...} chat commands as plain text in, text out (no game classes), so the whole command surface
 * is tested headlessly. The Minecraft glue only passes the raw argument string, the actor and the trader the player
 * is looking at, and prints the reply. Every rule is enforced below in {@link CompanyLedger}, not here.
 *
 * <p>Anything that moves money (deposit, pay, payroll run, invoice payment, dissolution) first shows a summary and a
 * one-minute confirm token; the token is the idempotency key, so a double click commits once.
 */
public final class CompanyConsole {
    /** The Lightman's Currency trader a player is looking at, as read server-side from the world. {@code owner} is null
     *  for an admin/creative trader, whose sales LcShops burns instead of paying anyone. */
    public record ShopRef(String trader, String name, UUID owner) {}
    public interface ShopProbe { Optional<ShopRef> lookedAt(UUID player); }
    public record Notice(UUID player, String text) {}
    public record Reply(boolean ok, List<String> lines, String confirm, List<Notice> notices) {
        static Reply ok(String... lines) { return new Reply(true, List.of(lines), null, List.of()); }
        static Reply fail(String line) { return new Reply(false, List.of(line), null, List.of()); }
    }

    private interface Action { Reply run(String idem) throws LedgerError; }
    private record Pending(UUID player, Action action, long expires) {}

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final String SOURCE = "minecraft";

    private final Ledger ledger;
    private final CompanyLedger co;
    private final Money money;
    private final LongSupplier clock;
    private final ShopProbe shops;
    private final Map<String, Pending> pending = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    public CompanyConsole(Ledger ledger, Money money, LongSupplier clock, ShopProbe shops) {
        this.ledger = ledger;
        this.co = new CompanyLedger(ledger);
        this.money = money;
        this.clock = clock;
        this.shops = shops == null ? p -> Optional.empty() : shops;
    }

    public CompanyLedger companies() { return co; }

    private static final class Usage extends Exception {
        Usage(String m) { super(m); }
    }

    public Reply run(Actor a, String raw) {
        try {
            List<String> t = tokens(raw == null ? "" : raw);
            if (t.isEmpty() || t.get(0).equalsIgnoreCase("help")) return help();
            String cmd = t.get(0).toLowerCase(Locale.ROOT);
            List<String> r = t.subList(1, t.size());
            return switch (cmd) {
                case "list" -> list(a);
                case "create" -> create(a, r);
                case "info" -> info(a, company(rest(r, 0)));
                case "members" -> members(a, company(rest(r, 0)));
                case "invite" -> { need(r, 2, "invite <company> <player> [role]"); yield done(co.invite(a, cid(r.get(0)), player(r.get(1)), role(r.size() > 2 ? r.get(2) : "employee"), SOURCE), invited(r)); }
                case "accept", "decline" -> answer(a, cmd.equals("accept"), rest(r, 0));
                case "revoke" -> { need(r, 2, "revoke <company> <player>"); yield done(co.revokeInvite(a, cid(r.get(0)), player(r.get(1)), SOURCE), null); }
                case "role" -> { need(r, 3, "role <company> <player> <role>"); yield done(co.setRole(a, cid(r.get(0)), player(r.get(1)), role(r.get(2)), SOURCE), null); }
                case "remove" -> { need(r, 2, "remove <company> <player>"); yield done(co.remove(a, cid(r.get(0)), player(r.get(1)), SOURCE), null); }
                case "leave" -> leave(a, rest(r, 0));
                case "grant" -> grant(a, r);
                case "rename" -> { need(r, 2, "rename <company> \"<new name>\""); yield done(co.rename(a, cid(r.get(0)), rest(r, 1), SOURCE), null); }
                case "balance" -> balance(a, company(rest(r, 0)));
                case "history" -> history(a, company(rest(r, 0)));
                case "deposit" -> deposit(a, r);
                case "pay" -> pay(a, r);
                case "payroll" -> payroll(a, r);
                case "invoice" -> invoice(a, r);
                case "invoices" -> invoices(a, r);
                case "shop" -> shop(a, r);
                case "shops" -> shopList(a, company(rest(r, 0)));
                case "dissolve" -> dissolve(a, r, false);
                case "confirm" -> { need(r, 1, "confirm <token>"); yield confirm(a, r.get(0)); }
                case "admin" -> admin(a, r);
                default -> Reply.fail("Unknown company command. Try /company help.");
            };
        } catch (Usage u) {
            return Reply.fail(u.getMessage());
        } catch (LedgerError e) {
            return Reply.fail(e.getMessage());
        } catch (IllegalArgumentException e) {
            return Reply.fail(e.getMessage());
        }
    }

    // ---------------------------------------------------------------- commands

    private Reply help() {
        return Reply.ok("Companies: a business with its own Indigo Credits treasury.",
            "/company list | create \"<name>\" [about] | info|members|balance|history <company>",
            "/company invite <company> <player> [role] | accept|decline <company> | revoke|remove <company> <player>",
            "/company role <company> <player> owner|director|manager|accountant|employee | leave <company> | rename <company> \"<name>\"",
            "/company deposit <company> <amount> [note] | pay <company> player|company <name> <amount> [note]",
            "/company payroll <company> [set <player> <amount|0> | run <period>] | grant <company> <accountant> on|off",
            "/company invoice <company> player|company <name> <amount> [memo] | invoices [company] | invoice pay|void <number>",
            "/company shop assign <company> | shop unassign | shop info (look at the trader) | shops <company>",
            "/company dissolve <company> [player|company <name>]",
            "Put names with spaces in quotes: \"Acme Power\".");
    }

    private Reply list(Actor a) throws Usage {
        UUID me = self(a);
        List<String> out = new ArrayList<>();
        for (Company c : co.state().memberships(me)) out.add(c.name() + " - " + c.members().get(me).role().label() + status(c));
        for (Company c : co.state().invitesFor(me)) out.add("Invitation: " + c.name() + " as " + c.invites().get(me).label() + " (/company accept \"" + c.name() + "\")");
        if (out.isEmpty()) out.add("You are not in a company. Start one with /company create \"<name>\".");
        return new Reply(true, out, null, List.of());
    }

    private Reply create(Actor a, List<String> r) throws Usage, LedgerError {
        need(r, 1, "create \"<name>\" [description]");
        Ledger.Result res = co.create(a, r.get(0), r.size() > 1 ? rest(r, 1) : null, SOURCE, null);
        Company c = co.state().get(meta(res.tx(), "company")).orElseThrow();
        return Reply.ok("Founded " + c.name() + ". You are its owner.", "Company id: " + c.id(),
            "Invite people with /company invite \"" + c.name() + "\" <player> [role].");
    }

    private Reply info(Actor a, Company c) throws LedgerError {
        Member m = co.check(a, c.id(), Perm.VIEW);
        List<String> owners = new ArrayList<>();
        for (Member x : c.members().values()) if (x.role() == Role.OWNER) owners.add(name(x.uuid()));
        List<String> out = new ArrayList<>(List.of(c.name() + status(c) + " - your role: " + m.role().label(),
            "Founded by " + name(c.founder()) + " on " + when(c.created()) + ". Owners: " + String.join(", ", owners) + ".",
            c.members().size() + " member(s), " + co.state().shopsOf(c.id()).size() + " shop(s). Id " + c.id()));
        if (c.description() != null) out.add(c.description());
        return new Reply(true, out, null, List.of());
    }

    private Reply members(Actor a, Company c) throws LedgerError {
        co.check(a, c.id(), Perm.VIEW);
        List<String> out = new ArrayList<>(List.of(c.name() + " members:"));
        for (Member x : c.members().values())
            out.add(name(x.uuid()) + " - " + x.role().label() + (x.payGrant() ? " (can pay)" : "") + ", since " + when(x.since()));
        for (Map.Entry<UUID, Role> e : c.invites().entrySet()) out.add(name(e.getKey()) + " - invited as " + e.getValue().label());
        return new Reply(true, out, null, List.of());
    }

    private Reply answer(Actor a, boolean accept, String ref) throws Usage, LedgerError {
        if (ref.isBlank()) throw new Usage((accept ? "accept" : "decline") + " <company>");
        Company c = co.state().byName(ref).orElseThrow(() -> new LedgerError("no_invite", "You have no invitation from \"" + ref + "\"."));
        Role role = c.invites().get(a.player());
        co.answerInvite(a, c.id(), accept, SOURCE);
        return Reply.ok(accept ? "You joined " + c.name() + " as " + (role == null ? "a member" : role.label()) + "." : "Invitation declined.");
    }

    private Reply leave(Actor a, String ref) throws Usage, LedgerError {
        Company c = company(ref);
        UUID me = self(a);
        if (!c.members().containsKey(me)) throw new LedgerError("not_member", "You are not a member of " + c.name() + ".");
        return ask(a, "Leave " + c.name() + "? Shops you run for it go back to paying you personally.", idem -> done(co.leave(a, c.id(), SOURCE), "You left " + c.name() + "."));
    }

    private Reply grant(Actor a, List<String> r) throws Usage, LedgerError {
        need(r, 3, "grant <company> <accountant> on|off");
        boolean on = switch (r.get(2).toLowerCase(Locale.ROOT)) {
            case "on", "yes", "true" -> true;
            case "off", "no", "false" -> false;
            default -> throw new Usage("grant <company> <accountant> on|off");
        };
        return done(co.grantPay(a, cid(r.get(0)), player(r.get(1)), on, SOURCE), null);
    }

    private Reply balance(Actor a, Company c) throws LedgerError {
        co.check(a, c.id(), Perm.VIEW_FINANCE);
        return Reply.ok(c.name() + " treasury: " + money.format(ledger.accountBalance(c.id())) + status(c));
    }

    private Reply history(Actor a, Company c) throws LedgerError {
        co.check(a, c.id(), Perm.VIEW_FINANCE);
        List<String> out = new ArrayList<>(List.of(c.name() + " treasury " + money.format(ledger.accountBalance(c.id())) + ". Recent activity:"));
        for (Ledger.Tx tx : ledger.history(c.id(), 12)) out.add(when(tx.ts()) + "  " + describe(tx, c.id()));
        return new Reply(true, out, null, List.of());
    }

    private Reply deposit(Actor a, List<String> r) throws Usage, LedgerError {
        need(r, 2, "deposit <company> <amount> [note]");
        Company c = company(r.get(0));
        long amt = money.parse(r.get(1));
        String note = r.size() > 2 ? rest(r, 2) : "deposit";
        return ask(a, "Pay " + money.format(amt) + " from your wallet into " + c.name() + "?", idem -> {
            Ledger.Result res = co.deposit(a, c.id(), amt, note, SOURCE, idem);
            return paid(res, "Paid " + money.format(amt) + " into " + c.name() + ".", List.of());
        });
    }

    private Reply pay(Actor a, List<String> r) throws Usage, LedgerError {
        need(r, 4, "pay <company> player|company <name> <amount> [note]");
        Company c = company(r.get(0));
        String to = target(r.get(1), r.get(2));
        long amt = money.parse(r.get(3));
        String note = r.size() > 4 ? rest(r, 4) : null;
        co.check(a, c.id(), Perm.PAY);
        String toName = ledger.displayName(to);
        return ask(a, "Pay " + money.format(amt) + " from " + c.name() + " to " + toName + (note == null ? "" : " (\"" + note + "\")") + "?", idem -> {
            Ledger.Result res = co.pay(a, c.id(), to, amt, note == null ? "company payment" : note, SOURCE, idem);
            List<Notice> n = to.startsWith("mc:") ? List.of(new Notice(UUID.fromString(to.substring(3)),
                "You received " + money.format(amt) + " from " + c.name() + (note == null ? "." : ": " + note))) : List.of();
            return paid(res, "Paid " + money.format(amt) + " from " + c.name() + " to " + toName + ". Treasury: "
                + money.format(ledger.accountBalance(c.id())) + ".", n);
        });
    }

    private Reply payroll(Actor a, List<String> r) throws Usage, LedgerError {
        need(r, 1, "payroll <company> [set <player> <amount|0> | run <period>]");
        Company c = company(r.get(0));
        if (r.size() == 1) {
            co.check(a, c.id(), Perm.VIEW_FINANCE);
            List<String> out = new ArrayList<>(List.of(c.name() + " payroll:"));
            long total = 0;
            for (Map.Entry<UUID, Long> e : c.payroll().entrySet()) {
                out.add(name(e.getKey()) + " - " + money.format(e.getValue()));
                total += e.getValue();
            }
            out.add(c.payroll().isEmpty() ? "No one is on the payroll." : "Total per run " + money.format(total) + "; treasury " + money.format(ledger.accountBalance(c.id())) + ".");
            return new Reply(true, out, null, List.of());
        }
        switch (r.get(1).toLowerCase(Locale.ROOT)) {
            case "set" -> {
                need(r, 4, "payroll <company> set <player> <amount|0>");
                long amt = r.get(3).equals("0") ? 0 : money.parse(r.get(3));
                return done(co.setPayroll(a, c.id(), player(r.get(2)), amt, SOURCE), amt == 0 ? "Payroll line removed." : "Payroll line set to " + money.format(amt) + ".");
            }
            case "run" -> {
                need(r, 3, "payroll <company> run <period>");
                String run = r.get(2);
                co.check(a, c.id(), Perm.PAY);
                if (co.state().payrollRan(c.id(), run)) return Reply.fail("Payroll " + run + " was already paid. Nobody is paid twice.");
                long total = c.payroll().values().stream().mapToLong(Long::longValue).sum();
                return ask(a, "Run payroll " + run + " for " + c.name() + ": " + c.payroll().size() + " employee(s), " + money.format(total)
                    + ". Everyone is paid, or nobody is.", idem -> {
                    Ledger.Result res = co.runPayroll(a, c.id(), run, SOURCE);
                    List<Notice> n = new ArrayList<>();
                    for (Ledger.Posting p : res.tx().postings())
                        if (p.account().startsWith("mc:")) n.add(new Notice(UUID.fromString(p.account().substring(3)),
                            "Payday: " + money.format(p.amount()) + " from " + c.name() + " (" + run + ")."));
                    return paid(res, "Payroll " + run + " paid: " + (res.tx().postings().size() - 1) + " employee(s), " + money.format(total) + ".", n);
                });
            }
            default -> throw new Usage("payroll <company> [set <player> <amount|0> | run <period>]");
        }
    }

    private Reply invoice(Actor a, List<String> r) throws Usage, LedgerError {
        need(r, 2, "invoice <company> player|company <name> <amount> [memo] | invoice pay|void <number>");
        String sub = r.get(0).toLowerCase(Locale.ROOT);
        if (sub.equals("pay") || sub.equals("void")) {
            Invoice i = co.state().invoiceByNumber(number(r.get(1))).orElseThrow(() -> new LedgerError("no_such_invoice", "No such invoice."));
            if (sub.equals("void")) {
                co.voidInvoice(a, i.id(), r.size() > 2 ? rest(r, 2) : null, SOURCE);
                return Reply.ok("Invoice #" + i.number() + " voided.");
            }
            String issuer = ledger.displayName(i.issuer());
            return ask(a, "Pay invoice #" + i.number() + ": " + money.format(i.amount()) + " to " + issuer + (i.memo() == null ? "" : " for \"" + i.memo() + "\"")
                + " from " + ledger.displayName(i.to()) + "?", idem -> {
                Ledger.Result res = co.payInvoice(a, i.id(), SOURCE);
                return paid(res, res.replayed() ? "Invoice #" + i.number() + " was already paid (transaction " + res.tx().txid() + "). Not charged again."
                    : "Invoice #" + i.number() + " paid. Transaction " + res.tx().txid() + ".", List.of());
            });
        }
        need(r, 4, "invoice <company> player|company <name> <amount> [memo]");
        Company c = company(r.get(0));
        String to = target(r.get(1), r.get(2));
        long amt = money.parse(r.get(3));
        Ledger.Result res = co.createInvoice(a, c.id(), to, amt, r.size() > 4 ? rest(r, 4) : null, null, SOURCE, null);
        long n = Long.parseLong(meta(res.tx(), "number"));
        List<Notice> notice = to.startsWith("mc:") ? List.of(new Notice(UUID.fromString(to.substring(3)),
            c.name() + " sent you invoice #" + n + " for " + money.format(amt) + ". /company invoice pay " + n)) : List.of();
        return new Reply(true, List.of("Invoice #" + n + " for " + money.format(amt) + " sent to " + ledger.displayName(to) + "."), null, notice);
    }

    private Reply invoices(Actor a, List<String> r) throws Usage, LedgerError {
        String account;
        String title;
        if (r.isEmpty()) {
            account = Ledger.acct(self(a));
            title = "Invoices addressed to you:";
        } else {
            Company c = company(rest(r, 0));
            co.check(a, c.id(), Perm.VIEW_FINANCE);
            account = c.id();
            title = c.name() + " invoices:";
        }
        List<String> out = new ArrayList<>(List.of(title));
        for (Invoice i : co.state().invoicesFor(account)) {
            boolean issued = i.issuer().equals(account);
            out.add("#" + i.number() + " " + (issued ? "to " + ledger.displayName(i.to()) : "from " + ledger.displayName(i.issuer())) + "  "
                + money.format(i.amount()) + "  " + i.status().name().toLowerCase(Locale.ROOT) + (i.memo() == null ? "" : "  " + i.memo())
                + (i.paidTx() == null ? "" : "  tx " + i.paidTx().substring(0, 8)));
            if (out.size() > 15) break;
        }
        if (out.size() == 1) out.add("None.");
        return new Reply(true, out, null, List.of());
    }

    private Reply shop(Actor a, List<String> r) throws Usage, LedgerError {
        need(r, 1, "shop assign <company> | shop unassign | shop info");
        UUID me = self(a);
        ShopRef s = shops.lookedAt(me).orElseThrow(() -> new Usage("Look at a Lightman's Currency trader (within 5 blocks) and try again."));
        switch (r.get(0).toLowerCase(Locale.ROOT)) {
            case "assign" -> {
                need(r, 2, "shop assign <company>");
                Company c = company(rest(r, 1));
                if (s.owner() == null)   // an admin/creative trader: LcShops burns its sales, so it can never pay a company
                    return Reply.fail(s.name() + " is an admin shop: its sales go to the server, so it can't pay a company.");
                co.assignShop(a, c.id(), s.trader(), s.name(), s.owner(), SOURCE);
                return Reply.ok(s.name() + " now pays " + c.name() + ". Sales go to the company treasury, not your wallet.",
                    "Let employees restock it with the shop's own ally settings; they never touch the treasury.");
            }
            case "unassign" -> {
                Shop cur = co.state().shop(s.trader()).orElseThrow(() -> new LedgerError("shop_not_assigned", "That shop is not assigned to a company."));
                co.unassignShop(a, s.trader(), null, SOURCE);
                return Reply.ok(s.name() + " no longer pays " + ledger.displayName(cur.company()) + ". Sales go to its owner again.");
            }
            case "info" -> {
                Optional<Shop> cur = co.state().shop(s.trader());
                if (s.owner() == null) return Reply.ok(s.name() + " (trader " + s.trader() + "): admin shop, its sales go to the server");
                return Reply.ok(s.name() + " (trader " + s.trader() + ", owner " + name(s.owner()) + "): "
                    + cur.map(x -> "pays " + ledger.displayName(x.company()) + " since " + when(x.since())).orElse("personal shop, pays its owner"));
            }
            default -> throw new Usage("shop assign <company> | shop unassign | shop info");
        }
    }

    private Reply shopList(Actor a, Company c) throws LedgerError {
        co.check(a, c.id(), Perm.VIEW);
        List<String> out = new ArrayList<>(List.of(c.name() + " shops:"));
        for (Shop s : co.state().shopsOf(c.id())) out.add(s.name() + " (trader " + s.trader() + ", run by " + name(s.operator()) + ")");
        if (out.size() == 1) out.add("None.");
        return new Reply(true, out, null, List.of());
    }

    private Reply dissolve(Actor a, List<String> r, boolean staff) throws Usage, LedgerError {
        need(r, 1, "dissolve <company> [player|company <name>]");
        Company c = company(r.get(0));
        String to = r.size() >= 3 ? target(r.get(1), r.get(2)) : null;
        if (!staff) co.check(a, c.id(), Perm.GOVERN);
        long bal = ledger.accountBalance(c.id());
        if (bal > 0 && to == null)
            return Reply.fail(c.name() + " still holds " + money.format(bal) + ". Name who receives it: /company dissolve \"" + c.name() + "\" player|company <name>");
        return ask(a, "Dissolve " + c.name() + "?" + (bal > 0 ? " Its " + money.format(bal) + " goes to " + ledger.displayName(to) + "." : "")
            + " Shops stop paying it and open invoices are voided. This can't be undone.", idem -> {
            Ledger.Result res = co.dissolve(a, c.id(), to, staff ? "dissolved by staff" : "dissolved by owner", SOURCE, idem);
            return paid(res, c.name() + " is dissolved.", List.of());
        });
    }

    private Reply admin(Actor a, List<String> r) throws Usage, LedgerError {
        if (!a.staff()) return Reply.fail("Staff only.");
        need(r, 2, "admin suspend|activate <company> <reason> | admin owner <company> <player> <reason> | admin dissolve <company> [player|company <name>]");
        Company c = company(r.get(1));
        return switch (r.get(0).toLowerCase(Locale.ROOT)) {
            case "suspend", "activate" -> {
                need(r, 3, "admin suspend|activate <company> <reason>");
                yield done(co.setSuspended(a, c.id(), r.get(0).equalsIgnoreCase("suspend"), rest(r, 2), "admin"), null);
            }
            case "owner" -> {
                need(r, 4, "admin owner <company> <player> <reason>");
                yield done(co.staffAddOwner(a, c.id(), player(r.get(2)), rest(r, 3), "admin"), null);
            }
            case "dissolve" -> dissolve(a, r.subList(1, r.size()), true);
            default -> throw new Usage("admin suspend|activate|owner|dissolve ...");
        };
    }

    private Reply confirm(Actor a, String token) {
        Pending p;
        synchronized (pending) {
            pending.values().removeIf(x -> x.expires() < clock.getAsLong());
            p = pending.get(token);
        }
        if (p == null || !p.player().equals(a.player())) return Reply.fail("That confirmation has expired. Run the command again.");
        try {
            return p.action().run("cmd:" + token);
        } catch (LedgerError e) {
            return Reply.fail(e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private Reply ask(Actor a, String summary, Action action) throws Usage {
        UUID me = self(a);
        byte[] b = new byte[6];
        random.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        synchronized (pending) {
            long now = clock.getAsLong();
            pending.values().removeIf(x -> x.expires() < now);
            pending.put(token, new Pending(me, action, now + 60_000));
        }
        return new Reply(true, List.of(summary), "/company confirm " + token, List.of());
    }

    private Reply paid(Ledger.Result res, String msg, List<Notice> notices) {
        if (res.replayed()) return Reply.ok("Already done: that request was processed once (transaction " + res.tx().txid() + ").");
        return new Reply(true, List.of(msg), null, notices);
    }

    private Reply done(Ledger.Tx tx, String msg) {
        return Reply.ok(msg != null ? msg : "Done: " + tx.reason() + ".");
    }

    private String invited(List<String> r) {
        return "Invited " + r.get(1) + ". They accept with /company accept \"" + co.state().get(cid(r.get(0))).map(Company::name).orElse(r.get(0)) + "\".";
    }

    private String describe(Ledger.Tx tx, String company) {
        Ledger.Posting p = tx.postingFor(company);
        String amt = p == null ? "" : "  " + money.signed(p.amount());
        var m = tx.meta();
        return switch (tx.type()) {
            case "company_create" -> "Founded by " + ledger.displayName(tx.actor().startsWith("mc:") ? tx.actor() : "?");
            case "company_member" -> name(UUID.fromString(meta(tx, "member"))) + ": " + tx.reason();
            case "company_invite" -> name(UUID.fromString(meta(tx, "member"))) + ": " + tx.reason();
            case "company_rename" -> "Renamed from " + meta(tx, "old");
            case "company_status" -> "Status " + meta(tx, "status").toLowerCase(Locale.ROOT) + ": " + tx.reason();
            case "company_transfer" -> (p != null && p.amount() > 0 ? "From " + ledger.displayName(meta(tx, "from")) : "To " + ledger.displayName(meta(tx, "to")))
                + amt + (tx.reason() == null ? "" : "  " + tx.reason());
            case "payroll_set" -> "Payroll " + name(UUID.fromString(meta(tx, "member"))) + " = " + money.format(m.get("amount").getAsLong());
            case "payroll_run" -> "Payroll " + meta(tx, "run") + " (" + (tx.postings().size() - 1) + " paid)" + amt;
            case "invoice_create" -> "Invoice #" + meta(tx, "number") + " to " + ledger.displayName(meta(tx, "to")) + "  " + money.format(m.get("amount").getAsLong());
            case "invoice_pay" -> "Invoice #" + meta(tx, "number") + " paid" + amt;
            case "invoice_void" -> "Invoice #" + meta(tx, "number") + " voided";
            case "shop_assign" -> "Shop " + meta(tx, "trader") + " " + meta(tx, "op") + "ed";
            case "shop_settle" -> "Shop sale: " + meta(tx, "item") + amt;
            case "company_dissolve" -> "Dissolved" + amt;
            default -> tx.type() + amt;
        };
    }

    private static String meta(Ledger.Tx tx, String k) { return Json.str(tx.meta(), k); }

    private String status(Company c) {
        return c.status() == Status.ACTIVE ? "" : " [" + c.status().name().toLowerCase(Locale.ROOT) + "]";
    }

    private String name(UUID u) { return ledger.account(u).map(Ledger.Account::name).orElse("?"); }

    private static String when(String ts) { return WHEN.format(Instant.parse(ts)); }

    private Company company(String ref) throws Usage, LedgerError {
        if (ref == null || ref.isBlank()) throw new Usage("Which company? Use its name (in quotes if it has spaces).");
        Optional<Company> c = ref.startsWith(Companies.PREFIX) ? co.state().get(ref) : co.state().byName(ref);
        return c.orElseThrow(() -> new LedgerError("no_such_company", "No company called \"" + ref + "\"."));
    }

    private String cid(String ref) {
        Optional<Company> c = ref.startsWith(Companies.PREFIX) ? co.state().get(ref) : co.state().byName(ref);
        return c.map(Company::id).orElseThrow(() -> new IllegalArgumentException("No company called \"" + ref + "\"."));
    }

    private UUID player(String name) {
        return ledger.byName(name).map(Ledger.Account::uuid)
            .orElseThrow(() -> new IllegalArgumentException("No Indigo account called \"" + name + "\". They need to have joined once."));
    }

    private String target(String kind, String name) throws Usage, LedgerError {
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "player" -> Ledger.acct(player(name));
            case "company" -> company(name).id();
            default -> throw new Usage("Say player or company before the name.");
        };
    }

    private static Role role(String s) throws Usage {
        Role r = Role.parse(s);
        if (r == null) throw new Usage("Roles: owner, director, manager, accountant, employee.");
        return r;
    }

    private static long number(String s) throws Usage {
        try { return Long.parseLong(s.startsWith("#") ? s.substring(1) : s); } catch (NumberFormatException e) { throw new Usage("Invoice numbers look like 12 or #12."); }
    }

    private static UUID self(Actor a) throws Usage {
        if (a.player() == null) throw new Usage("That needs a player.");
        return a.player();
    }

    private static void need(List<String> r, int n, String usage) throws Usage {
        if (r.size() < n) throw new Usage("Usage: /company " + usage);
    }

    private static String rest(List<String> r, int from) { return from >= r.size() ? "" : String.join(" ", r.subList(from, r.size())); }

    /** Splits on spaces; "double quotes" keep spaces together. */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        boolean quoted = false, any = false;
        for (char ch : s.toCharArray()) {
            if (ch == '"') { quoted = !quoted; any = true; continue; }
            if (Character.isWhitespace(ch) && !quoted) {
                if (any) out.add(b.toString());
                b.setLength(0);
                any = false;
                continue;
            }
            b.append(ch);
            any = true;
        }
        if (any) out.add(b.toString());
        return out;
    }
}
