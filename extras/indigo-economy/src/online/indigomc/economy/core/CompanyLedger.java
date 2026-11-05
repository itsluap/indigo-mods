package online.indigomc.economy.core;

import com.google.gson.JsonObject;
import online.indigomc.economy.core.Companies.*;
import online.indigomc.economy.core.Ledger.LedgerError;
import online.indigomc.economy.core.Ledger.Result;
import online.indigomc.economy.core.Ledger.Tx;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Every company mutation (Indigo companies, milestone 1). Each method validates against the current state and
 * commits one journal event under the ledger's own lock, so company actions are serialized with every other
 * balance change and a rejected action commits nothing. Role checks live here, in server code: the Discord bot
 * and the chat commands are only front ends.
 *
 * <p>Money moves only between {@code mc:<uuid>} and {@code company:<uuid>} accounts through the same postings,
 * non-negative checks and idempotency keys as player transfers. There is no second balance store.
 */
public final class CompanyLedger {
    /** Who is asking. {@code player} is the Minecraft account the request acts for (null for console); staff bypass nothing but may use the admin operations. */
    public record Actor(String id, UUID player, boolean staff) {
        public static Actor player(UUID u) { return new Actor("mc:" + u, u, false); }
        public static Actor discord(String discordId, UUID linked) { return new Actor("discord:" + discordId, linked, false); }
        public static Actor staff(String id, UUID u) { return new Actor(id, u, true); }
    }

    private static final Pattern RUN_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:_.\\-]{0,47}$");
    private static final Pattern TRADER = Pattern.compile("^[0-9]{1,19}$");

    private final Ledger l;
    private final Companies c;

    public CompanyLedger(Ledger ledger) {
        this.l = ledger;
        this.c = ledger.companies;
    }

    public Companies state() { return c; }

    // ---------------------------------------------------------------- identity and lifecycle

    public Result create(Actor a, String name, String description, String source, String idem) throws LedgerError {
        l.lock.lock();
        try {
            UUID founder = requirePlayer(a);
            String clean = Companies.cleanName(name);
            String fp = Ledger.fingerprint("company_create", founder, Companies.key(clean));
            Result replay = l.replay(source, idem, fp);
            if (replay != null) return replay;
            if (!Companies.validName(clean))
                throw new LedgerError("invalid_name", "Company names are 3-32 characters: letters, digits, spaces and & ' . -");
            if (c.byName(clean).isPresent()) throw new LedgerError("name_taken", "A company called \"" + clean + "\" already exists.");
            l.requireAccount(founder, "founder");
            if (c.ownedBy(founder) >= Companies.MAX_OWNED)
                throw new LedgerError("limit_reached", "You already own " + Companies.MAX_OWNED + " companies.");
            String id = Companies.PREFIX + UUID.randomUUID();
            Tx tx = l.commit(l.base("company_create", a.id(), source, "company created", idem, fp, List.of(),
                Json.obj("company", id, "name", clean, "founder", founder.toString(), "description", Ledger.clean(description))));
            return new Result(tx, false);
        } finally {
            l.lock.unlock();
        }
    }

    public Tx invite(Actor a, String company, UUID who, Role role, String source) throws LedgerError {
        l.lock.lock();
        try {
            Company co = notDissolved(company);
            Member me = need(co, a, Perm.MEMBERS);
            if (role == null) throw new LedgerError("invalid_role", "Unknown role.");
            if (!Companies.canManage(me.role(), role)) throw denied("Your role can't invite someone as " + role.label() + ".");
            l.requireAccount(who, "player");
            if (co.members().containsKey(who)) throw new LedgerError("already_member", "They are already a member.");
            if (co.members().size() >= Companies.MAX_MEMBERS) throw new LedgerError("limit_reached", "The company is full.");
            if (co.invites().size() >= Companies.MAX_INVITES && !co.invites().containsKey(who))
                throw new LedgerError("limit_reached", "Too many open invitations.");
            return l.commit(l.base("company_invite", a.id(), source, "invited as " + role.label(), null, null, List.of(),
                Json.obj("company", company, "member", who.toString(), "role", role.name(), "op", "invite")));
        } finally {
            l.lock.unlock();
        }
    }

    /** The invited player accepts ({@code accept}) or declines; a manager can revoke with {@link #revokeInvite}. */
    public Tx answerInvite(Actor a, String company, boolean accept, String source) throws LedgerError {
        l.lock.lock();
        try {
            Company co = notDissolved(company);
            UUID me = requirePlayer(a);
            Role role = co.invites().get(me);
            if (role == null) throw new LedgerError("no_invite", "You have no invitation from that company.");
            if (!accept)
                return l.commit(l.base("company_invite", a.id(), source, "invitation declined", null, null, List.of(),
                    Json.obj("company", company, "member", me.toString(), "op", "decline")));
            if (co.members().size() >= Companies.MAX_MEMBERS) throw new LedgerError("limit_reached", "The company is full.");
            return l.commit(l.base("company_member", a.id(), source, "joined as " + role.label(), null, null, List.of(),
                Json.obj("company", company, "member", me.toString(), "role", role.name(), "op", "join")));
        } finally {
            l.lock.unlock();
        }
    }

    public Tx revokeInvite(Actor a, String company, UUID who, String source) throws LedgerError {
        l.lock.lock();
        try {
            Company co = notDissolved(company);
            Member me = need(co, a, Perm.MEMBERS);
            Role role = co.invites().get(who);
            if (role == null) throw new LedgerError("no_invite", "There is no open invitation for them.");
            if (!Companies.canManage(me.role(), role)) throw denied("Your role can't revoke that invitation.");
            return l.commit(l.base("company_invite", a.id(), source, "invitation revoked", null, null, List.of(),
                Json.obj("company", company, "member", who.toString(), "op", "revoke")));
        } finally {
            l.lock.unlock();
        }
    }

    /** Changes a member's role. Staff may set any role (ownership recovery); nobody may demote the last owner. */
    public Tx setRole(Actor a, String company, UUID who, Role role, String source) throws LedgerError {
        l.lock.lock();
        try {
            Company co = notDissolved(company);
            if (role == null) throw new LedgerError("invalid_role", "Unknown role.");
            Member target = co.members().get(who);
            if (target == null) throw new LedgerError("not_member", "They are not a member.");
            if (!a.staff()) {
                Member me = need(co, a, Perm.MEMBERS);
                if (!Companies.canManage(me.role(), target.role()) || !Companies.canManage(me.role(), role))
                    throw denied("Your role can't change a " + target.role().label() + " to " + role.label() + ".");
            }
            if (target.role() == role) throw new LedgerError("no_change", "They already have that role.");
            if (target.role() == Role.OWNER && co.owners() == 1)
                throw new LedgerError("last_owner", "A company always needs an owner. Make someone else an owner first.");
            return l.commit(l.base("company_member", a.id(), source, target.role().label() + " -> " + role.label(), null, null, List.of(),
                Json.obj("company", company, "member", who.toString(), "role", role.name(), "old", target.role().name(), "op", "role")));
        } finally {
            l.lock.unlock();
        }
    }

    /** Staff recovery for a company whose owners are gone: makes an existing account an owner (joining if needed). */
    public Tx staffAddOwner(Actor a, String company, UUID who, String reason, String source) throws LedgerError {
        l.lock.lock();
        try {
            if (!a.staff()) throw denied("Staff only.");
            if (reason == null || reason.isBlank()) throw new LedgerError("reason_required", "Staff actions need a reason.");
            Company co = notDissolved(company);
            l.requireAccount(who, "player");
            Member m = co.members().get(who);
            if (m != null && m.role() == Role.OWNER) throw new LedgerError("no_change", "They are already an owner.");
            String op = m == null ? "join" : "role";
            return l.commit(l.base("company_member", a.id(), source, reason, null, null, List.of(),
                Json.obj("company", company, "member", who.toString(), "role", Role.OWNER.name(), "op", op, "staff", true)));
        } finally {
            l.lock.unlock();
        }
    }

    public Tx remove(Actor a, String company, UUID who, String source) throws LedgerError {
        l.lock.lock();
        try {
            Company co = notDissolved(company);
            Member target = co.members().get(who);
            if (target == null) throw new LedgerError("not_member", "They are not a member.");
            if (who.equals(a.player())) throw new LedgerError("use_leave", "Use leave to leave a company.");
            Member me = need(co, a, Perm.MEMBERS);
            if (!Companies.canManage(me.role(), target.role())) throw denied("Your role can't remove a " + target.role().label() + ".");
            if (target.role() == Role.OWNER && co.owners() == 1) throw new LedgerError("last_owner", "A company always needs an owner.");
            return l.commit(l.base("company_member", a.id(), source, "removed", null, null, List.of(), departure(company, who, "remove")));
        } finally {
            l.lock.unlock();
        }
    }

    public Tx leave(Actor a, String company, String source) throws LedgerError {
        l.lock.lock();
        try {
            Company co = notDissolved(company);
            UUID me = requirePlayer(a);
            Member m = co.members().get(me);
            if (m == null) throw new LedgerError("not_member", "You are not a member.");
            if (m.role() == Role.OWNER && co.owners() == 1)
                throw new LedgerError("last_owner", "You are the last owner. Make someone else an owner, or dissolve the company.");
            return l.commit(l.base("company_member", a.id(), source, "left", null, null, List.of(), departure(company, me, "leave")));
        } finally {
            l.lock.unlock();
        }
    }

    /** A departing member's payroll line goes, and shops they operate for the company go back to paying them personally. */
    private JsonObject departure(String company, UUID who, String op) {
        List<String> shops = new ArrayList<>();
        for (Shop s : c.shopsOf(company)) if (s.operator().equals(who)) shops.add(s.trader());
        JsonObject m = Json.obj("company", company, "member", who.toString(), "op", op);
        if (!shops.isEmpty()) m.add("unassigned_shops", Companies.ids(shops));
        return m;
    }

    /** Owners and directors may let an accountant spend from the treasury, and take it back. */
    public Tx grantPay(Actor a, String company, UUID who, boolean pay, String source) throws LedgerError {
        l.lock.lock();
        try {
            Company co = notDissolved(company);
            need(co, a, Perm.PAYROLL_EDIT);
            Member target = co.members().get(who);
            if (target == null || target.role() != Role.ACCOUNTANT)
                throw new LedgerError("invalid_role", "Only accountants get a payment grant; owners and directors can always pay.");
            if (target.payGrant() == pay) throw new LedgerError("no_change", "Nothing to change.");
            return l.commit(l.base("company_member", a.id(), source, pay ? "payment grant on" : "payment grant off", null, null, List.of(),
                Json.obj("company", company, "member", who.toString(), "pay", pay, "op", "grant")));
        } finally {
            l.lock.unlock();
        }
    }

    public Tx rename(Actor a, String company, String name, String source) throws LedgerError {
        l.lock.lock();
        try {
            Company co = notDissolved(company);
            need(co, a, Perm.GOVERN);
            String clean = Companies.cleanName(name);
            if (!Companies.validName(clean))
                throw new LedgerError("invalid_name", "Company names are 3-32 characters: letters, digits, spaces and & ' . -");
            Optional<Company> other = c.byName(clean);
            if (other.isPresent() && !other.get().id().equals(company))
                throw new LedgerError("name_taken", "A company called \"" + clean + "\" already exists.");
            if (clean.equals(co.name())) throw new LedgerError("no_change", "That is already the name.");
            return l.commit(l.base("company_rename", a.id(), source, "renamed", null, null, List.of(),
                Json.obj("company", company, "name", clean, "old", co.name())));
        } finally {
            l.lock.unlock();
        }
    }

    /** Staff only: a suspended company keeps receiving money (nothing is lost) but cannot spend, invoice or assign shops. */
    public Tx setSuspended(Actor a, String company, boolean suspended, String reason, String source) throws LedgerError {
        l.lock.lock();
        try {
            if (!a.staff()) throw denied("Staff only.");
            if (reason == null || reason.isBlank()) throw new LedgerError("reason_required", "Staff actions need a reason.");
            Company co = notDissolved(company);
            Status want = suspended ? Status.SUSPENDED : Status.ACTIVE;
            if (co.status() == want) throw new LedgerError("no_change", "Nothing to change.");
            return l.commit(l.base("company_status", a.id(), source, reason, null, null, List.of(),
                Json.obj("company", company, "status", want.name())));
        } finally {
            l.lock.unlock();
        }
    }

    /**
     * Dissolves a company: an owner (or staff) must name where any remaining treasury goes, so money is never destroyed
     * or paid out silently. In the same journal line every shop assignment ends, open invoices it issued or owes are
     * voided, and invitations and payroll lines are cleared. The name stays reserved.
     */
    public Result dissolve(Actor a, String company, String payoutTo, String reason, String source, String idem) throws LedgerError {
        l.lock.lock();
        try {
            String fp = Ledger.fingerprint("company_dissolve", company, payoutTo);
            Result replay = l.replay(source, idem, fp);
            if (replay != null) return replay;
            Company co = notDissolved(company);
            if (!a.staff()) need(co, a, Perm.GOVERN);
            long bal = l.accountBalance(company);
            List<Ledger.Posting> postings = new ArrayList<>();
            if (bal > 0) {
                if (payoutTo == null)
                    throw new LedgerError("balance_not_zero", "The treasury still holds money. Pay it out, or name who receives it.");
                String target = recipient(payoutTo, company);
                postings.add(l.posting(company, -bal));
                postings.add(l.posting(target, bal));
            }
            List<String> voided = new ArrayList<>();
            for (Invoice i : c.invoicesFor(company)) if (i.status() == InvoiceStatus.OPEN) voided.add(i.id());
            List<String> shops = new ArrayList<>();
            for (Shop s : c.shopsOf(company)) shops.add(s.trader());
            JsonObject m = Json.obj("company", company, "name", co.name(), "payout_to", bal > 0 ? payoutTo : null, "payout", bal);
            m.add("unassigned_shops", Companies.ids(shops));
            m.add("voided_invoices", Companies.ids(voided));
            Tx tx = l.commit(l.base("company_dissolve", a.id(), source, reason == null || reason.isBlank() ? "dissolved" : reason, idem, fp, postings, m));
            return new Result(tx, false);
        } finally {
            l.lock.unlock();
        }
    }

    // ---------------------------------------------------------------- treasury

    /** Anyone with an account may pay a company that is not dissolved (a deposit, a purchase, a settlement). */
    public Result deposit(Actor a, String company, long amount, String reason, String source, String idem) throws LedgerError {
        l.lock.lock();
        try {
            UUID from = requirePlayer(a);
            String fp = Ledger.fingerprint("company_deposit", from, company, amount, reason);
            Result replay = l.replay(source, idem, fp);
            if (replay != null) return replay;
            l.checkAmount(amount);
            notDissolved(company);
            l.requireAccount(from, "payer");
            return new Result(move(a, Ledger.acct(from), company, amount, reason, source, idem, fp, company), false);
        } finally {
            l.lock.unlock();
        }
    }

    /**
     * Pays from the treasury to a player ({@code mc:<uuid>}) or another company ({@code company:<uuid>}). Needs
     * {@link Perm#PAY} and an active company; the full amount or nothing; idempotent on (source, idem).
     */
    public Result pay(Actor a, String company, String toAccount, long amount, String reason, String source, String idem) throws LedgerError {
        l.lock.lock();
        try {
            String fp = Ledger.fingerprint("company_pay", company, toAccount, amount, reason);
            Result replay = l.replay(source, idem, fp);
            if (replay != null) return replay;
            l.checkAmount(amount);
            Company co = active(company);
            need(co, a, Perm.PAY);
            String target = recipient(toAccount, company);
            return new Result(move(a, company, target, amount, reason, source, idem, fp, company), false);
        } finally {
            l.lock.unlock();
        }
    }

    private Tx move(Actor a, String from, String to, long amount, String reason, String source, String idem, String fp, String company)
            throws LedgerError {
        long have = l.accountBalance(from);
        if (have < amount) throw new LedgerError("insufficient_funds", "Not enough money: balance is " + have + ".");
        if (l.accountBalance(to) + amount > Money.MAX) throw new LedgerError("overflow", "The recipient's balance would exceed the limit.");
        return l.commit(l.base("company_transfer", a.id(), source, reason, idem, fp,
            List.of(l.posting(from, -amount), l.posting(to, amount)), Json.obj("company", company, "from", from, "to", to)));
    }

    // ---------------------------------------------------------------- payroll

    /** Sets a member's standing payroll amount; 0 removes the line. */
    public Tx setPayroll(Actor a, String company, UUID who, long amount, String source) throws LedgerError {
        l.lock.lock();
        try {
            Company co = active(company);
            need(co, a, Perm.PAYROLL_EDIT);
            if (!co.members().containsKey(who)) throw new LedgerError("not_member", "Only members can be on the payroll.");
            if (amount != 0) l.checkAmount(amount);
            if (co.payroll().getOrDefault(who, 0L) == amount) throw new LedgerError("no_change", "Nothing to change.");
            return l.commit(l.base("payroll_set", a.id(), source, amount == 0 ? "payroll line removed" : "payroll line set", null, null, List.of(),
                Json.obj("company", company, "member", who.toString(), "amount", amount)));
        } finally {
            l.lock.unlock();
        }
    }

    /**
     * Pays every payroll line in ONE journal line: all employees are paid, or (short treasury, anything invalid)
     * nobody is and nothing is recorded, so there is never a half-paid run and never hidden debt. The run id
     * (e.g. {@code 2026-W40}) is the idempotency key: running the same period again returns the original payment,
     * even if the lines changed meanwhile. A rejected run does not use up its id.
     */
    public Result runPayroll(Actor a, String company, String runId, String source) throws LedgerError {
        l.lock.lock();
        try {
            if (runId == null || !RUN_ID.matcher(runId).matches())
                throw new LedgerError("invalid_run_id", "Use a period id like 2026-W40 (letters, digits, : _ . -; up to 48).");
            Company co = c.get(company).orElseThrow(() -> new LedgerError("no_such_company", "No such company."));
            String fp = Ledger.fingerprint("payroll_run", company, runId);
            Result replay = l.replay("payroll", company + "|" + runId, fp);
            if (replay != null) return replay;
            co = active(company);
            need(co, a, Perm.PAY);
            if (co.payroll().isEmpty()) throw new LedgerError("payroll_empty", "No one is on the payroll.");
            long total = 0;
            for (long v : co.payroll().values()) total = Math.addExact(total, v);
            long have = l.accountBalance(company);
            if (have < total)
                throw new LedgerError("insufficient_funds", "The treasury has " + have + " but this payroll needs " + total + ". Nobody was paid.");
            List<Ledger.Posting> postings = new ArrayList<>();
            postings.add(l.posting(company, -total));
            com.google.gson.JsonArray lines = new com.google.gson.JsonArray();
            for (Map.Entry<UUID, Long> e : co.payroll().entrySet()) {
                String acct = Ledger.acct(e.getKey());
                if (l.accountBalance(acct) + e.getValue() > Money.MAX) throw new LedgerError("overflow", "A payee's balance would exceed the limit.");
                postings.add(l.posting(acct, e.getValue()));
                lines.add(Json.obj("member", e.getKey().toString(), "amount", e.getValue()));
            }
            JsonObject m = Json.obj("company", company, "run", runId, "total", total);
            m.add("lines", lines);
            Tx tx = l.commit(l.base("payroll_run", a.id(), "payroll", "payroll " + runId, company + "|" + runId, fp, postings, m));
            return new Result(tx, false);
        } finally {
            l.lock.unlock();
        }
    }

    // ---------------------------------------------------------------- invoices

    public Result createInvoice(Actor a, String issuer, String toAccount, long amount, String memo, String due, String source, String idem)
            throws LedgerError {
        l.lock.lock();
        try {
            String fp = Ledger.fingerprint("invoice_create", issuer, toAccount, amount, memo);
            Result replay = l.replay(source, idem, fp);
            if (replay != null) return replay;
            l.checkAmount(amount);
            Company co = active(issuer);
            need(co, a, Perm.INVOICE);
            String to = recipient(toAccount, issuer);
            String id = UUID.randomUUID().toString();
            Tx tx = l.commit(l.base("invoice_create", a.id(), source, "invoice #" + (c.invoiceCount() + 1), idem, fp, List.of(),
                Json.obj("company", issuer, "invoice", id, "number", c.invoiceCount() + 1, "to", to, "amount", amount,
                    "memo", Ledger.clean(memo), "due", due == null ? null : Ledger.clean(due))));
            return new Result(tx, false);
        } finally {
            l.lock.unlock();
        }
    }

    public Tx voidInvoice(Actor a, String invoiceId, String reason, String source) throws LedgerError {
        l.lock.lock();
        try {
            Invoice i = c.invoice(invoiceId).orElseThrow(() -> new LedgerError("no_such_invoice", "No such invoice."));
            if (!a.staff()) need(c.get(i.issuer()).orElseThrow(), a, Perm.INVOICE);
            if (i.status() != InvoiceStatus.OPEN) throw new LedgerError("invoice_not_open", "That invoice is already " + i.status().name().toLowerCase(Locale.ROOT) + ".");
            return l.commit(l.base("invoice_void", a.id(), source, reason == null || reason.isBlank() ? "voided" : reason, null, null, List.of(),
                Json.obj("company", i.issuer(), "invoice", i.id(), "number", i.number())));
        } finally {
            l.lock.unlock();
        }
    }

    /**
     * Pays an open invoice in full from its recipient: the player themself, or a company member with
     * {@link Perm#PAY}. Money moves and the invoice becomes PAID in the same journal line, whose txid is recorded on
     * the invoice. The invoice id is the idempotency key, so a second attempt returns that same payment (replayed)
     * and can never charge twice.
     */
    public Result payInvoice(Actor a, String invoiceId, String source) throws LedgerError {
        l.lock.lock();
        try {
            Invoice i = c.invoice(invoiceId).orElseThrow(() -> new LedgerError("no_such_invoice", "No such invoice."));
            String fp = Ledger.fingerprint("invoice_pay", i.id());
            Result replay = l.replay("invoice", i.id(), fp);
            if (replay != null) return replay;
            if (i.status() != InvoiceStatus.OPEN) throw new LedgerError("invoice_not_open", "That invoice is " + i.status().name().toLowerCase(Locale.ROOT) + ".");
            if (Companies.isCompanyAccount(i.to())) need(active(i.to()), a, Perm.PAY);
            else if (a.player() == null || !Ledger.acct(a.player()).equals(i.to())) throw denied("Only the person invoiced can pay it.");
            notDissolved(i.issuer());
            long have = l.accountBalance(i.to());
            if (have < i.amount()) throw new LedgerError("insufficient_funds", "Not enough money: balance is " + have + ".");
            if (l.accountBalance(i.issuer()) + i.amount() > Money.MAX) throw new LedgerError("overflow", "The issuer's balance would exceed the limit.");
            Tx tx = l.commit(l.base("invoice_pay", a.id(), "invoice", "invoice #" + i.number() + (i.memo() == null ? "" : ": " + i.memo()), i.id(), fp,
                List.of(l.posting(i.to(), -i.amount()), l.posting(i.issuer(), i.amount())),
                Json.obj("company", i.issuer(), "invoice", i.id(), "number", i.number(), "from", i.to(), "to", i.issuer())));
            return new Result(tx, false);
        } finally {
            l.lock.unlock();
        }
    }

    // ---------------------------------------------------------------- company shops

    /**
     * Routes a Lightman's Currency trader's revenue to the company. The actor must hold {@link Perm#SHOPS} AND be the
     * trader's current LC owner ({@code lcOwner}, read server-side from the trader, never from the client), so nobody
     * can redirect someone else's shop and nobody outside the company can point a shop at it.
     */
    public Tx assignShop(Actor a, String company, String trader, String shopName, UUID lcOwner, String source) throws LedgerError {
        l.lock.lock();
        try {
            if (trader == null || !TRADER.matcher(trader).matches()) throw new LedgerError("bad_trader", "That is not a trader.");
            Company co = active(company);
            need(co, a, Perm.SHOPS);
            if (lcOwner == null || !lcOwner.equals(a.player()))
                throw denied("Only the shop's owner can assign its revenue to a company.");
            Optional<Shop> existing = c.shop(trader);
            if (existing.isPresent())
                throw new LedgerError("shop_already_assigned", existing.get().company().equals(company)
                    ? "That shop already pays this company." : "That shop is assigned to another company. Unassign it there first.");
            return l.commit(l.base("shop_assign", a.id(), source, "shop assigned", null, null, List.of(),
                Json.obj("company", company, "trader", trader, "shop", Ledger.clean(shopName), "operator", lcOwner.toString(), "op", "assign")));
        } finally {
            l.lock.unlock();
        }
    }

    /** Ends an assignment. Allowed for {@link Perm#SHOPS} holders, the member operating the shop, or staff. */
    public Tx unassignShop(Actor a, String trader, String reason, String source) throws LedgerError {
        l.lock.lock();
        try {
            Shop s = c.shop(trader).orElseThrow(() -> new LedgerError("shop_not_assigned", "That shop is not assigned to a company."));
            Company co = c.get(s.company()).orElseThrow();
            boolean operator = s.operator().equals(a.player());
            if (!a.staff() && !operator) need(co, a, Perm.SHOPS);
            return l.commit(l.base("shop_assign", a.id(), source, reason == null || reason.isBlank() ? "shop unassigned" : reason, null, null, List.of(),
                Json.obj("company", s.company(), "trader", trader, "op", "unassign")));
        } finally {
            l.lock.unlock();
        }
    }

    // ---------------------------------------------------------------- read access (role-checked)

    /** Returns the member record if the actor may do {@code p} in this company (staff: a synthetic owner view). */
    public Member check(Actor a, String company, Perm p) throws LedgerError {
        l.lock.lock();
        try {
            Company co = c.get(company).orElseThrow(() -> new LedgerError("no_such_company", "No such company."));
            if (a.staff()) return new Member(a.player(), Role.OWNER, true, co.created());
            return need(co, a, p);
        } finally {
            l.lock.unlock();
        }
    }

    // ---------------------------------------------------------------- helpers (lock held)

    private Member need(Company co, Actor a, Perm p) throws LedgerError {
        Member m = co.member(a.player()).orElse(null);
        if (m == null) throw new LedgerError("not_member", "You are not a member of " + co.name() + ".");
        if (!Companies.can(m, p)) throw denied("Your role (" + m.role().label() + ") can't do that in " + co.name() + ".");
        return m;
    }

    private static LedgerError denied(String why) { return new LedgerError("forbidden", why); }

    private static UUID requirePlayer(Actor a) throws LedgerError {
        if (a == null || a.player() == null) throw new LedgerError("forbidden", "That needs a player account.");
        return a.player();
    }

    private Company notDissolved(String company) throws LedgerError {
        Company co = c.get(company).orElseThrow(() -> new LedgerError("no_such_company", "No such company."));
        if (co.status() == Status.DISSOLVED) throw new LedgerError("company_inactive", co.name() + " has been dissolved.");
        return co;
    }

    private Company active(String company) throws LedgerError {
        Company co = notDissolved(company);
        if (co.status() != Status.ACTIVE) throw new LedgerError("company_inactive", co.name() + " is suspended by staff.");
        return co;
    }

    /** A payable account other than {@code self}: an existing player, or a company that is not dissolved. */
    private String recipient(String account, String self) throws LedgerError {
        if (account == null) throw new LedgerError("no_such_account", "No recipient.");
        if (account.equals(self)) throw new LedgerError("same_account", "A company can't pay itself.");
        if (Companies.isCompanyAccount(account)) {
            notDissolved(account);
            return account;
        }
        if (!account.startsWith("mc:")) throw new LedgerError("no_such_account", "No such account.");
        UUID u;
        try { u = UUID.fromString(account.substring(3)); } catch (IllegalArgumentException e) { throw new LedgerError("no_such_account", "No such account."); }
        l.requireAccount(u, "recipient");
        return account;
    }
}
