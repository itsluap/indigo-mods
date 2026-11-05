package online.indigomc.economy.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Append-only, hash-chained JSON Lines journal. One line per committed event.
 *
 * <p>Each line is the event body with {@code "hash"} spliced in as the last member, where
 * {@code hash = sha256(prevHash + "\n" + body)} and {@code body} is the exact serialized JSON
 * of the event without the hash member. Lines are written and fsynced before the caller is
 * told the event committed, so an acknowledged event survives a crash.
 *
 * <p>Load rules (bounded corruption behaviour): a final segment with no trailing newline is a
 * torn write that was never acknowledged; it is moved to a side file and truncated. Any
 * complete line that fails to parse, breaks the sequence or breaks the hash chain is fatal:
 * the ledger refuses to open rather than guessing.
 */
public final class Journal implements AutoCloseable {
    public static final String GENESIS = "0".repeat(64);
    private static final String HASH_KEY = ",\"hash\":\"";

    private final Path file;
    private FileChannel channel;
    private String lastHash = GENESIS;
    private long lastSeq = 0;
    private String tornNote = null;

    public Journal(Path file) {
        this.file = file;
    }

    public record Line(long seq, JsonObject body, String hash) {}

    /** Reads, verifies and returns every committed line, then opens the file for appending. */
    public List<Line> open() throws IOException {
        Files.createDirectories(file.getParent());
        List<Line> out = new ArrayList<>();
        if (Files.exists(file)) {
            byte[] raw = Files.readAllBytes(file);
            int lastNl = -1;
            for (int i = raw.length - 1; i >= 0; i--) if (raw[i] == '\n') { lastNl = i; break; }
            if (lastNl != raw.length - 1) {
                // torn tail: bytes after the last newline were never acknowledged
                byte[] tail = java.util.Arrays.copyOfRange(raw, lastNl + 1, raw.length);
                Path side = file.resolveSibling(file.getFileName() + ".torn-" + System.currentTimeMillis());
                Files.write(side, tail);
                try (FileChannel c = FileChannel.open(file, StandardOpenOption.WRITE)) {
                    c.truncate(lastNl + 1);
                    c.force(true);
                }
                tornNote = "truncated " + tail.length + " unacknowledged byte(s) to " + side.getFileName();
                raw = java.util.Arrays.copyOf(raw, lastNl + 1);
            }
            String text = new String(raw, StandardCharsets.UTF_8);
            int lineNo = 0;
            for (String s : text.split("\n", -1)) {
                if (s.isEmpty()) continue;
                lineNo++;
                Line l = parseAndCheck(s, lastHash, lastSeq + 1, lineNo);
                out.add(l);
                lastHash = l.hash();
                lastSeq = l.seq();
            }
        }
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        return out;
    }

    static Line parseAndCheck(String s, String prevHash, long expectSeq, int lineNo) throws IOException {
        int at = s.lastIndexOf(HASH_KEY);
        if (at < 0 || !s.endsWith("\"}")) throw new CorruptJournalException("line " + lineNo + ": no hash member");
        String body = s.substring(0, at) + "}";
        String hash = s.substring(at + HASH_KEY.length(), s.length() - 2);
        String want = hash(prevHash, body);
        if (!want.equals(hash)) throw new CorruptJournalException("line " + lineNo + ": hash chain broken");
        JsonObject obj;
        try {
            obj = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new CorruptJournalException("line " + lineNo + ": unparseable: " + e.getMessage());
        }
        long seq = obj.get("seq").getAsLong();
        if (seq != expectSeq) throw new CorruptJournalException("line " + lineNo + ": seq " + seq + " expected " + expectSeq);
        if (!prevHash.equals(obj.get("prev").getAsString())) throw new CorruptJournalException("line " + lineNo + ": prev mismatch");
        return new Line(seq, obj, hash);
    }

    public long nextSeq() { return lastSeq + 1; }
    public String lastHash() { return lastHash; }
    public String tornNote() { return tornNote; }
    public Path file() { return file; }

    /**
     * Commits one event. {@code body} must already carry "seq" == nextSeq() and "prev" == lastHash().
     * Returns only after the bytes are forced to stable storage.
     */
    public String append(JsonObject body) throws IOException {
        String json = Json.GSON.toJson(body);
        String h = hash(lastHash, json);
        String line = json.substring(0, json.length() - 1) + HASH_KEY + h + "\"}\n";
        ByteBuffer buf = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
        while (buf.hasRemaining()) channel.write(buf);
        channel.force(true);
        lastHash = h;
        lastSeq = body.get("seq").getAsLong();
        return h;
    }

    public static String hash(String prev, String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prev.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
            md.update(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() throws IOException {
        if (channel != null) channel.close();
    }

    public static final class CorruptJournalException extends IOException {
        public CorruptJournalException(String m) { super(m); }
    }
}
