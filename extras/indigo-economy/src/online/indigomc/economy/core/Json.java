package online.indigomc.economy.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {}

    public static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) put(o, (String) kv[i], kv[i + 1]);
        return o;
    }

    public static void put(JsonObject o, String k, Object v) {
        if (v == null) return;
        if (v instanceof String s) o.addProperty(k, s);
        else if (v instanceof Number n) o.addProperty(k, n);
        else if (v instanceof Boolean b) o.addProperty(k, b);
        else if (v instanceof JsonObject j) o.add(k, j);
        else if (v instanceof JsonArray j) o.add(k, j);
        else o.addProperty(k, v.toString());
    }

    public static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}
