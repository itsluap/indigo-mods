package online.indigomc.economy.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Company state, rebuilt only from journal events (Indigo companies, milestone 1).
 *
 * <p>A company is identified by {@code company:<uuid>}, which is also its treasury account in the one
 * {@link Ledger}. Its identity, members, roles, invitations, payroll lines, invoices and shop assignments
 * are zero-posting journal events in the same hash-chained file as the money, so every company fact has
 * the same crash, replay and audit guarantees as a balance, and an invoice payment or a payroll run moves
 * money and changes company state in one fsynced line.
 *
 * <p>This class only holds state and answers questions; every mutation is validated and committed by
 * {@link CompanyLedger} under the ledger lock. {@link #apply} is called by the ledger for each committed
 * or replayed event, in journal order, so the state is a pure function of the journal.
 */
public final class Companies {
    public static final String PREFIX = "company:";
    public static final int MAX_MEMBERS = 100;
    public static final int MAX_INVITES = 50;
    /** Active or suspended companies one player may be an owner of. */
    public static final int MAX_OWNED = 5;
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 &'.\\-]{1,30}[A-Za-z0-9.]$");

    /** Built-in roles, most senior first. Permissions are fixed in {@link #can}; there are no custom permission strings. */
    public enum Role {
        OWNER, DIRECTOR, MANAGER, ACCOUNTANT, EMPLOYEE;

        public String label() { return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT); }

        public static Role parse(String s) {
            if (s == null) return null;
            try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
        }
    }

    public enum Perm {
        /** Company info and member list. */
        VIEW,
        /** Treasury balance, transactions, invoices, payroll lines. */
        VIEW_FINANCE,
        /** Spend from the treasury: pay players/companies, pay invoices addressed to the company, run payroll. */
        PAY,
        /** Set or remove standing payroll lines; turn an accountant's payment grant on or off. */
        PAYROLL_EDIT,
        /** Create and void the company's invoices. */
        INVOICE,
        /** Assign and unassign shops to the company. */
        SHOPS,
        /** Invite, remove and change roles (within {@link #canManage}). */
        MEMBERS,
        /** Rename and dissolve. */
        GOVERN
    }

    public enum Status { ACTIVE, SUSPENDED, DISSOLVED }

    public record Member(UUID uuid, Role role, boolean payGrant, String since) {}

    public record Company(String id, String name, String key, UUID founder, String created, Status status, String description,
                          Map<UUID, Member> members, Map<UUID, Role> invites, Map<UUID, Long> payroll) {
        public Optional<Member> member(UUID u) { return Optional.ofNullable(u == null ? null : members.get(u)); }
        public long owners() { return members.values().stream().filter(m -> m.role() == Role.OWNER).count(); }
        public boolean active() { return status == Status.ACTIVE; }
    }

    public enum InvoiceStatus { OPEN, PAID, VOID }

    public record Invoice(String id, long number, String issuer, String to, long amount, String memo, String due, String created,
                          InvoiceStatus status, String paidTx, String paidBy, String voidReason) {}

    public record Shop(String trader, String company, UUID operator, String name, String since, String by) {}

    private final Map<String, Company> companies = new LinkedHashMap<>();
    private final Map<String, String> byKey = new HashMap<>();              // name key -> company id (dissolved names stay reserved)
    private final Map<String, Invoice> invoices = new LinkedHashMap<>();
    private final Map<String, Shop> shops = new LinkedHashMap<>();         // LC trader id -> assignment
    private final Map<String, Set<String>> payrollRuns = new HashMap<>();  // company -> run ids paid
    private long invoiceCount = 0;

    // ---------------------------------------------------------------- pure rules

    /** The whole permission table. Accountants can spend only while an owner or director has granted it. */
    public static boolean can(Member m, Perm p) {
        if (m == null) return false;
        Role r = m.role();
        return switch (p) {
            case VIEW -> true;
            case VIEW_FINANCE, INVOICE -> r != Role.EMPLOYEE;
            case PAY -> r == Role.OWNER || r == Role.DIRECTOR || (r == Role.ACCOUNTANT && m.payGrant());
            case PAYROLL_EDIT -> r == Role.OWNER || r == Role.DIRECTOR;
            case SHOPS -> r == Role.OWNER || r == Role.DIRECTOR || r == Role.MANAGER;
            case MEMBERS -> r == Role.OWNER || r == Role.DIRECTOR || r == Role.MANAGER;
            case GOVERN -> r == Role.OWNER;
        };
    }

    /** Which roles an actor may invite into, remove from, or move members between. */
    public static boolean canManage(Role actor, Role target) {
        return switch (actor) {
            case OWNER -> true;
            case DIRECTOR -> target == Role.MANAGER || target == Role.ACCOUNTANT || target == Role.EMPLOYEE;
            case MANAGER -> target == Role.EMPLOYEE;
            default -> false;
        };
    }

    /** Case-insensitive uniqueness key: lower case, single spaces. */
    public static String key(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static String cleanName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ");
    }

    public static boolean validName(String name) { return NAME.matcher(cleanName(name)).matches(); }

    public static boolean isCompanyAccount(String account) { return account != null && account.startsWith(PREFIX); }

    // ---------------------------------------------------------------- queries (callers hold the ledger lock or accept a racy read)

    public Optional<Company> get(String id) { return Optional.ofNullable(id == null ? null : companies.get(id)); }

    public Optional<Company> byName(String name) {
        String id = byKey.get(key(name));
        return id == null ? Optional.empty() : get(id);
    }

    public List<Company> all() { return new ArrayList<>(companies.values()); }

    /** Companies (not dissolved) this player belongs to. */
    public List<Company> memberships(UUID player) {
        List<Company> out = new ArrayList<>();
        for (Company c : companies.values()) if (c.status() != Status.DISSOLVED && c.members().containsKey(player)) out.add(c);
        return out;
    }

    public List<Company> invitesFor(UUID player) {
        List<Company> out = new ArrayList<>();
        for (Company c : companies.values()) if (c.status() != Status.DISSOLVED && c.invites().containsKey(player)) out.add(c);
        return out;
    }

    public long ownedBy(UUID player) {
        return companies.values().stream().filter(c -> c.status() != Status.DISSOLVED)
            .filter(c -> c.member(player).map(m -> m.role() == Role.OWNER).orElse(false)).count();
    }

    public Optional<Invoice> invoice(String id) { return Optional.ofNullable(id == null ? null : invoices.get(id)); }

    public Optional<Invoice> invoiceByNumber(long n) {
        for (Invoice i : invoices.values()) if (i.number() == n) return Optional.of(i);
        return Optional.empty();
    }

    /** Invoices issued by or addressed to {@code account}, newest first. */
    public List<Invoice> invoicesFor(String account) {
        List<Invoice> out = new ArrayList<>();
        for (Invoice i : invoices.values()) if (i.issuer().equals(account) || i.to().equals(account)) out.add(i);
        Collections.reverse(out);
        return out;
    }

    public Optional<Shop> shop(String trader) { return Optional.ofNullable(trader == null ? null : shops.get(trader)); }

    public List<Shop> shopsOf(String company) {
        List<Shop> out = new ArrayList<>();
        for (Shop s : shops.values()) if (s.company().equals(company)) out.add(s);
        return out;
    }

    public boolean payrollRan(String company, String runId) {
        return payrollRuns.getOrDefault(company, Set.of()).contains(runId);
    }

    public long invoiceCount() { return invoiceCount; }

    /** A stable digest of all company state, used to prove replay gives exactly the same state. */
    public String digest() {
        StringBuilder b = new StringBuilder();
        for (Company c : companies.values()) b.append(c).append('\n');
        new TreeMap<>(byKey).forEach((k, v) -> b.append(k).append('=').append(v).append('\n'));
        for (Invoice i : invoices.values()) b.append(i).append('\n');
        for (Shop s : shops.values()) b.append(s).append('\n');
        new TreeMap<>(payrollRuns).forEach((k, v) -> b.append(k).append(new TreeSet<>(v)).append('\n'));
        b.append(invoiceCount);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- replay

    static boolean handles(String type) {
        return type.startsWith("company_") || type.startsWith("invoice_") || type.startsWith("payroll_") || type.equals("shop_assign");
    }

    /**
     * Applies one committed event. Any inconsistency (an event that could not have been validated against the
     * replayed state) throws, which makes the ledger refuse to open, exactly like a money posting mismatch.
     */
    void apply(Ledger.Tx tx) {
        JsonObject m = tx.meta();
        String cid = Json.str(m, "company");
        switch (tx.type()) {
            case "company_create" -> {
                if (companies.containsKey(cid)) fail(tx, "company exists");
                String key = key(Json.str(m, "name"));
                if (byKey.containsKey(key)) fail(tx, "name taken");
                UUID founder = uuid(m, "founder");
                Map<UUID, Member> members = new LinkedHashMap<>();
                members.put(founder, new Member(founder, Role.OWNER, false, tx.ts()));
                companies.put(cid, new Company(cid, Json.str(m, "name"), key, founder, tx.ts(), Status.ACTIVE, Json.str(m, "description"),
                    freeze(members), Map.of(), Map.of()));
                byKey.put(key, cid);
            }
            case "company_invite" -> {
                Company c = live(tx, cid);
                Map<UUID, Role> inv = new LinkedHashMap<>(c.invites());
                UUID who = uuid(m, "member");
                String op = Json.str(m, "op");
                if (op.equals("invite")) inv.put(who, role(tx, Json.str(m, "role")));
                else if (inv.remove(who) == null) fail(tx, "no such invite");
                put(c, c.members(), freeze(inv), c.payroll(), c.status(), c.name());
            }
            case "company_member" -> {
                Company c = live(tx, cid);
                UUID who = uuid(m, "member");
                String op = Json.str(m, "op");
                Map<UUID, Member> mem = new LinkedHashMap<>(c.members());
                Map<UUID, Role> inv = new LinkedHashMap<>(c.invites());
                Map<UUID, Long> pay = new LinkedHashMap<>(c.payroll());
                Member old = mem.get(who);
                switch (op) {
                    case "join" -> {
                        if (old != null) fail(tx, "already a member");
                        inv.remove(who);
                        mem.put(who, new Member(who, role(tx, Json.str(m, "role")), false, tx.ts()));
                    }
                    case "role" -> {
                        if (old == null) fail(tx, "not a member");
                        mem.put(who, new Member(who, role(tx, Json.str(m, "role")), false, old.since()));
                    }
                    case "grant" -> {
                        if (old == null || old.role() != Role.ACCOUNTANT) fail(tx, "grant needs an accountant");
                        mem.put(who, new Member(who, old.role(), m.get("pay").getAsBoolean(), old.since()));
                    }
                    case "remove", "leave" -> {
                        if (old == null) fail(tx, "not a member");
                        mem.remove(who);
                        pay.remove(who);
                        unassignListed(tx, m, cid);
                    }
                    default -> fail(tx, "unknown member op " + op);
                }
                if (c.status() != Status.DISSOLVED && mem.values().stream().noneMatch(x -> x.role() == Role.OWNER)) fail(tx, "no owner left");
                put(c, freeze(mem), freeze(inv), freeze(pay), c.status(), c.name());
            }
            case "company_rename" -> {
                Company c = live(tx, cid);
                String key = key(Json.str(m, "name"));
                String holder = byKey.get(key);
                if (holder != null && !holder.equals(cid)) fail(tx, "name taken");
                byKey.remove(c.key(), cid);
                byKey.put(key, cid);
                put(c, c.members(), c.invites(), c.payroll(), c.status(), Json.str(m, "name"));
            }
            case "company_status" -> {
                Company c = live(tx, cid);
                put(c, c.members(), c.invites(), c.payroll(), Status.valueOf(Json.str(m, "status")), c.name());
            }
            case "company_dissolve" -> {
                Company c = live(tx, cid);
                unassignListed(tx, m, cid);
                if (m.has("voided_invoices"))
                    for (JsonElement e : m.getAsJsonArray("voided_invoices")) setInvoice(tx, e.getAsString(), InvoiceStatus.VOID, null, null, "company dissolved");
                if (!shopsOf(cid).isEmpty()) fail(tx, "dissolved with shops still assigned");
                put(c, c.members(), Map.of(), Map.of(), Status.DISSOLVED, c.name());
            }
            case "company_transfer" -> { }   // money only; postings already applied
            case "payroll_set" -> {
                Company c = live(tx, cid);
                UUID who = uuid(m, "member");
                if (!c.members().containsKey(who)) fail(tx, "payroll for a non-member");
                Map<UUID, Long> pay = new LinkedHashMap<>(c.payroll());
                long amount = m.get("amount").getAsLong();
                if (amount == 0) pay.remove(who); else pay.put(who, amount);
                put(c, c.members(), c.invites(), freeze(pay), c.status(), c.name());
            }
            case "payroll_run" -> {
                live(tx, cid);
                if (!payrollRuns.computeIfAbsent(cid, k -> new HashSet<>()).add(Json.str(m, "run"))) fail(tx, "payroll run repeated");
            }
            case "invoice_create" -> {
                live(tx, Json.str(m, "company"));
                String id = Json.str(m, "invoice");
                long n = m.get("number").getAsLong();
                if (invoices.containsKey(id) || n != invoiceCount + 1) fail(tx, "invoice id/number out of order");
                invoiceCount = n;
                invoices.put(id, new Invoice(id, n, cid, Json.str(m, "to"), m.get("amount").getAsLong(), Json.str(m, "memo"), Json.str(m, "due"),
                    tx.ts(), InvoiceStatus.OPEN, null, null, null));
            }
            case "invoice_pay" -> {
                Invoice i = invoices.get(Json.str(m, "invoice"));
                if (i == null) fail(tx, "no such invoice");
                Ledger.Posting in = tx.postingFor(i.issuer()), out = tx.postingFor(i.to());
                if (in == null || out == null || in.amount() != i.amount() || out.amount() != -i.amount() || tx.postings().size() != 2)
                    fail(tx, "invoice payment postings do not match the invoice");
                setInvoice(tx, i.id(), InvoiceStatus.PAID, tx.txid(), Json.str(m, "from"), null);
            }
            case "invoice_void" -> setInvoice(tx, Json.str(m, "invoice"), InvoiceStatus.VOID, null, null, tx.reason());
            case "shop_assign" -> {
                String trader = Json.str(m, "trader");
                if (Json.str(m, "op").equals("assign")) {
                    live(tx, cid);
                    if (shops.containsKey(trader)) fail(tx, "shop already assigned");
                    shops.put(trader, new Shop(trader, cid, uuid(m, "operator"), Json.str(m, "shop"), tx.ts(), tx.actor()));
                } else {
                    Shop s = shops.remove(trader);
                    if (s == null || !s.company().equals(cid)) fail(tx, "shop not assigned to this company");
                }
            }
            default -> fail(tx, "unknown company event");
        }
    }

    private void unassignListed(Ledger.Tx tx, JsonObject m, String cid) {
        if (!m.has("unassigned_shops")) return;
        for (JsonElement e : m.getAsJsonArray("unassigned_shops")) {
            Shop s = shops.remove(e.getAsString());
            if (s == null || !s.company().equals(cid)) fail(tx, "listed shop not assigned to this company");
        }
    }

    private void setInvoice(Ledger.Tx tx, String id, InvoiceStatus status, String paidTx, String paidBy, String voidReason) {
        Invoice i = invoices.get(id);
        if (i == null || i.status() != InvoiceStatus.OPEN) fail(tx, "invoice " + id + " is not open");
        invoices.put(id, new Invoice(i.id(), i.number(), i.issuer(), i.to(), i.amount(), i.memo(), i.due(), i.created(), status, paidTx, paidBy, voidReason));
    }

    private Company live(Ledger.Tx tx, String cid) {
        Company c = companies.get(cid);
        if (c == null) fail(tx, "no such company " + cid);
        if (c.status() == Status.DISSOLVED) fail(tx, "company is dissolved");
        return c;
    }

    private void put(Company c, Map<UUID, Member> members, Map<UUID, Role> invites, Map<UUID, Long> payroll, Status status, String name) {
        companies.put(c.id(), new Company(c.id(), name, key(name), c.founder(), c.created(), status, c.description(), members, invites, payroll));
    }

    private static <K, V> Map<K, V> freeze(Map<K, V> m) { return Collections.unmodifiableMap(new LinkedHashMap<>(m)); }

    private static UUID uuid(JsonObject m, String k) { return UUID.fromString(m.get(k).getAsString()); }

    private static Role role(Ledger.Tx tx, String s) {
        Role r = Role.parse(s);
        if (r == null) fail(tx, "unknown role " + s);
        return r;
    }

    private static void fail(Ledger.Tx tx, String why) {
        throw new IllegalStateException("seq " + tx.seq() + " (" + tx.type() + "): " + why);
    }

    /** Meta arrays listing shop ids, for events that unassign shops in the same line. */
    static JsonArray ids(Collection<String> ids) {
        JsonArray a = new JsonArray();
        for (String s : ids) a.add(s);
        return a;
    }
}
