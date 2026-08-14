package com.birditch.escapefromcraftov.common.id;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.regex.Pattern;

/// Namespaced identifier of the form `namespace:key`, e.g. `escapefromcraftov:healing_potion`.
///
/// Both parts must match `[a-z0-9_.-]+`.
public record NamespacedId(String namespace, String key) {

    private static final String DEFAULT_NAMESPACE = "escapefromcraftov";
    private static final Pattern VALID_PART = Pattern.compile("[a-z0-9_.-]+");

    public NamespacedId {
        Preconditions.checkNotNull(namespace, "namespace");
        Preconditions.checkNotNull(key, "key");
        Preconditions.checkArgument(VALID_PART.matcher(namespace).matches(),
                "namespace must match [a-z0-9_.-]+ but was: " + namespace);
        Preconditions.checkArgument(VALID_PART.matcher(key).matches(),
                "key must match [a-z0-9_.-]+ but was: " + key);
    }

    /// Creates an identifier in the default `escapefromcraftov` namespace.
    public static NamespacedId of(String key) {
        return new NamespacedId(DEFAULT_NAMESPACE, key);
    }

    /// Parses `"namespace:key"`; input without a colon is treated as a bare
    /// key in the default `escapefromcraftov` namespace.
    public static NamespacedId parse(String text) {
        Preconditions.checkNotNull(text, "text");
        int separator = text.indexOf(':');
        if (separator < 0) {
            return of(text);
        }
        return new NamespacedId(text.substring(0, separator), text.substring(separator + 1));
    }

    @Override
    public String toString() {
        return namespace + ":" + key;
    }
}
