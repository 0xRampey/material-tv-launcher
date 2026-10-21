package com.rampey.materialtv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Label matching for the target resolver. Pure Java so it is unit-testable off-device. */
final class Targets {
    private Targets() {}

    /** Whitespace as the host's Rust matcher defines it: Unicode White_Space, not just ASCII. */
    private static final String SPACE = "[\\s\\p{Z}\\u0085]";

    /**
     * Matching form of a label: case- and whitespace-insensitive and without the bracket characters
     * the host strips from agent-facing text, so a label quoted back exactly as it was displayed
     * ("Inbox 3" for "Inbox [3]") still matches. Must agree with the host's {@code normalized}.
     */
    static String normalize(String value) {
        if (value == null) return "";
        String s =
                value.replaceAll("[\\[\\]{}]", "")
                        .replaceAll(SPACE + "+", " ")
                        .replaceAll("^ | $", "")
                        .toLowerCase(Locale.ROOT);
        // A label truncated for display ends in an ellipsis; the text before it still names the
        // item. Only White_Space is trimmed (not String.trim's control characters), as on the host.
        if (s.endsWith("\u2026")) s = s.substring(0, s.length() - 1).replaceAll(" $", "");
        return s;
    }

    /**
     * Returns the indices of candidates matching {@code label}, best tier first. Each candidate is
     * {@code {name, hint}}: the text or content description, then the hint. Tiers are tried in
     * order and the first non-empty one wins: exact name, exact hint, name containing the label,
     * hint containing the label. Hints matter because a filled text field is labeled by its hint
     * ("Name") while its text is the value ("Ada"); ranking them after names keeps a button and a
     * field hint with the same word from becoming ambiguous.
     */
    static List<Integer> match(List<String[]> candidates, String label) {
        return search(candidates, label).hits;
    }

    /** The matches plus the tier that produced them: 0-1 exact name or hint, 2-3 containing. */
    static final class Hits {
        final List<Integer> hits = new ArrayList<>();
        int tier = 4;

        boolean exact() {
            return tier < 2;
        }
    }

    static Hits search(List<String[]> candidates, String label) {
        String needle = normalize(label);
        Hits out = new Hits();
        if (needle.isEmpty()) return out;
        for (int tier = 0; tier < 4 && out.hits.isEmpty(); tier++) {
            boolean exact = tier < 2;
            int field = tier % 2;
            for (int i = 0; i < candidates.size(); i++) {
                String[] names = candidates.get(i);
                String name = field < names.length ? normalize(names[field]) : "";
                if (!name.isEmpty() && (exact ? name.equals(needle) : name.contains(needle))) {
                    out.hits.add(i);
                    out.tier = tier;
                }
            }
        }
        return out;
    }
}
