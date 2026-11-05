package online.indigomc.economy;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Properties;

/**
 * config/indigo_economy-server.properties. Currency naming and the starting balance are human
 * policy decisions: the defaults here are lab placeholders only.
 */
public final class EconomyConfig {
    public final String singular, plural, bridgeBind, secretFile;
    public final int decimals, bridgePort, bridgeRate, linkTtlSeconds;
    public final long startingBalance;
    public final boolean bridgeEnabled, requireClientMod;
    public final String packVersion;
    /** Discord games (0.4.4): kill switch and caps, in whole Indigo Credits in the file. */
    public final boolean gamesEnabled;
    public final long gamesPlayerDailyIc, gamesGlobalDailyIc, gamesMaxAwardIc;
    /** Indigo companies (milestone 1): /company in game and the /v1/company bridge endpoints. Off by default. */
    public final boolean companiesEnabled;
    /** Player-run casinos (0.5.0, D61): switch, one-time licence fee and the highest bet per round, in whole Indigo Credits. */
    public final boolean casinoEnabled;
    public final long casinoLicenceIc, casinoMaxBetIc;
    /** Staff grants and takes from Discord (0.5.3): /v1/admin/adjust, off by default, capped per action in whole Indigo Credits. */
    public final boolean adminAdjustEnabled;
    public final long adminAdjustMaxIc;

    private static final String HEADER = """
        Indigo account economy. Policy (2026-09-27): Indigo Credits, 2 decimals, starting balance 0,
        transfers allowed (also to offline players who have an account), no fees or taxes.
        The Discord bridge is off unless enabled; it binds loopback by default and needs the secret file.""";

    private EconomyConfig(Properties p) {
        singular = p.getProperty("currency_singular", "Indigo Credit");
        plural = p.getProperty("currency_plural", "Indigo Credits");
        decimals = Integer.parseInt(p.getProperty("decimals", "2"));
        startingBalance = Long.parseLong(p.getProperty("starting_balance_minor", "0"));
        linkTtlSeconds = Integer.parseInt(p.getProperty("link_code_ttl_seconds", "300"));
        bridgeEnabled = Boolean.parseBoolean(p.getProperty("bridge_enabled", "false"));
        bridgeBind = p.getProperty("bridge_bind", "127.0.0.1");
        bridgePort = Integer.parseInt(p.getProperty("bridge_port", "25580"));
        bridgeRate = Integer.parseInt(p.getProperty("bridge_rate_per_minute", "60"));
        secretFile = p.getProperty("bridge_secret_file", "indigo_economy-bridge.secret");
        requireClientMod = Boolean.parseBoolean(p.getProperty("require_client_mod", "true"));
        packVersion = p.getProperty("pack_version", "0.5.2");
        gamesEnabled = Boolean.parseBoolean(p.getProperty("games_enabled", "false"));
        gamesPlayerDailyIc = Long.parseLong(p.getProperty("games_player_daily_ic", "50"));
        gamesGlobalDailyIc = Long.parseLong(p.getProperty("games_global_daily_ic", "2000"));
        gamesMaxAwardIc = Long.parseLong(p.getProperty("games_max_award_ic", "20"));
        companiesEnabled = Boolean.parseBoolean(p.getProperty("companies_enabled", "false"));
        casinoEnabled = Boolean.parseBoolean(p.getProperty("casino_enabled", "true"));
        casinoLicenceIc = Long.parseLong(p.getProperty("casino_licence_ic", "250"));
        casinoMaxBetIc = Long.parseLong(p.getProperty("casino_max_bet_ic", "100"));
        adminAdjustEnabled = Boolean.parseBoolean(p.getProperty("admin_adjust_enabled", "false"));
        adminAdjustMaxIc = Long.parseLong(p.getProperty("admin_adjust_max_ic", "1000"));
    }

    public static EconomyConfig load(Path configDir) throws IOException {
        Path f = configDir.resolve("indigo_economy-server.properties");
        Properties p = new Properties();
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); }
        }
        EconomyConfig c = new EconomyConfig(p);
        if (!Files.exists(f)) {
            Properties d = new Properties();
            d.setProperty("currency_singular", c.singular);
            d.setProperty("currency_plural", c.plural);
            d.setProperty("decimals", String.valueOf(c.decimals));
            d.setProperty("starting_balance_minor", String.valueOf(c.startingBalance));
            d.setProperty("link_code_ttl_seconds", String.valueOf(c.linkTtlSeconds));
            d.setProperty("bridge_enabled", String.valueOf(c.bridgeEnabled));
            d.setProperty("bridge_bind", c.bridgeBind);
            d.setProperty("bridge_port", String.valueOf(c.bridgePort));
            d.setProperty("bridge_rate_per_minute", String.valueOf(c.bridgeRate));
            d.setProperty("bridge_secret_file", c.secretFile);
            d.setProperty("require_client_mod", String.valueOf(c.requireClientMod));
            d.setProperty("pack_version", c.packVersion);
            d.setProperty("companies_enabled", String.valueOf(c.companiesEnabled));
            Files.createDirectories(configDir);
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) { d.store(w, HEADER); }
        }
        return c;
    }

    /** Reads the bridge secret, creating a random one (owner-only where the OS supports it) on first use. */
    public byte[] secret(Path configDir) throws IOException {
        Path f = configDir.resolve(secretFile);
        if (!Files.exists(f)) {
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            Files.writeString(f, HexFormat.of().formatHex(raw) + "\n", StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows lab: ACLs of the user profile apply
            }
        }
        return Files.readString(f, StandardCharsets.UTF_8).strip().getBytes(StandardCharsets.UTF_8);
    }
}
