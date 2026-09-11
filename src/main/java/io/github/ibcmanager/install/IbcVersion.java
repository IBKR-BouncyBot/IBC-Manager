package io.github.ibcmanager.install;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/** Numeric dotted IBC release version used for compatibility and release matching. */
final class IbcVersion implements Comparable<IbcVersion> {
    private static final Pattern VERSION_PATTERN = Pattern.compile("[0-9]+(?:\\.[0-9]+){1,3}");
    private static final int MAX_COMPONENT = 999_999;

    private final String text;
    private final int[] components;

    private IbcVersion(String text, int[] components) {
        this.text = text;
        this.components = components;
    }

    static IbcVersion parse(String value) throws IbcInstallationException {
        String normalized = Objects.requireNonNull(value, "value").trim();
        if (!VERSION_PATTERN.matcher(normalized).matches()) {
            throw new IbcInstallationException("IBC version is not a supported numeric release version: "
                    + normalized);
        }
        String[] parts = normalized.split("\\.", -1);
        int[] components = new int[parts.length];
        try {
            for (int index = 0; index < parts.length; index++) {
                if (parts[index].length() > 6) throw new NumberFormatException("component too long");
                components[index] = Integer.parseInt(parts[index]);
                if (components[index] > MAX_COMPONENT) throw new NumberFormatException("component too large");
            }
        } catch (NumberFormatException ex) {
            throw new IbcInstallationException("IBC version contains an invalid numeric component: "
                    + normalized, ex);
        }
        return new IbcVersion(normalized, components);
    }

    static IbcVersion parseTag(String tag) throws IbcInstallationException {
        String normalized = Objects.requireNonNull(tag, "tag").trim();
        if ((normalized.startsWith("v") || normalized.startsWith("V")) && normalized.length() > 1) {
            normalized = normalized.substring(1);
        }
        return parse(normalized);
    }

    boolean semanticallyEquals(IbcVersion other) {
        return compareTo(other) == 0;
    }

    String text() {
        return text;
    }

    @Override
    public int compareTo(IbcVersion other) {
        Objects.requireNonNull(other, "other");
        int length = Math.max(components.length, other.components.length);
        for (int index = 0; index < length; index++) {
            int left = index < components.length ? components[index] : 0;
            int right = index < other.components.length ? other.components[index] : 0;
            int comparison = Integer.compare(left, right);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof IbcVersion other && semanticallyEquals(other);
    }

    @Override
    public int hashCode() {
        int last = components.length - 1;
        while (last > 0 && components[last] == 0) last--;
        return Arrays.hashCode(Arrays.copyOf(components, last + 1));
    }

    @Override
    public String toString() {
        return text;
    }
}
