package com.birditch.escapefromcraftov.redis;

import com.birditch.escapefromcraftov.api.CraftovException;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.time.Instant;

/// JSON serialization for Redis payloads. Records serialize natively via Gson;
/// [Instant] values are encoded as epoch-millisecond numbers so payloads stay
/// compact and language-agnostic.
public final class JsonCodec {

  private static final int MAX_PAYLOAD_SNIPPET = 200;

  private final Gson gson;

  public JsonCodec() {
    this.gson = new GsonBuilder()
        .registerTypeAdapter(Instant.class, new InstantEpochMillisAdapter().nullSafe())
        .create();
  }

  public String toJson(Object value) {
    Preconditions.checkNotNull(value, "value");
    return gson.toJson(value);
  }

  /// Parses `json` into `type`. Any decoding failure — malformed JSON, a `null`
  /// literal, or record invariants rejecting the payload — surfaces as a
  /// [CraftovException] carrying the offending payload (truncated to
  /// [#MAX_PAYLOAD_SNIPPET] chars), so callers can treat "cannot decode"
  /// uniformly (for example: skip and log a malformed snapshot).
  public <T> T fromJson(String json, Class<T> type) {
    Preconditions.checkNotNull(json, "json");
    Preconditions.checkNotNull(type, "type");
    T parsed;
    try {
      parsed = gson.fromJson(json, type);
    } catch (RuntimeException e) {
      throw new CraftovException(parseError(json, type), e);
    }
    if (parsed == null) {
      throw new CraftovException(parseError(json, type));
    }
    return parsed;
  }

  private static String parseError(String json, Class<?> type) {
    return "Failed to parse JSON as " + type.getSimpleName() + ": " + snippet(json);
  }

  /// Truncates oversized payloads so a giant malformed blob never floods logs
  /// or exception messages.
  private static String snippet(String json) {
    if (json.length() <= MAX_PAYLOAD_SNIPPET) {
      return json;
    }
    return json.substring(0, MAX_PAYLOAD_SNIPPET) + "...(" + json.length() + " chars total)";
  }

  private static final class InstantEpochMillisAdapter extends TypeAdapter<Instant> {

    @Override
    public void write(JsonWriter out, Instant value) throws IOException {
      out.value(value.toEpochMilli());
    }

    @Override
    public Instant read(JsonReader in) throws IOException {
      return Instant.ofEpochMilli(in.nextLong());
    }
  }
}
