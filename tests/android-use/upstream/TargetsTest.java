package com.rampey.materialtv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TargetsTest {
    private static List<String[]> nodes(String[]... nodes) {
        return Arrays.asList(nodes);
    }

    @Test
    public void filledFieldIsFoundByItsHint() {
        List<String[]> scene = nodes(new String[] {"Ada", "Name"}, new String[] {"Save", ""});
        assertEquals(Collections.singletonList(0), Targets.match(scene, "name"));
    }

    @Test
    public void exactTextOutranksHintSoUniqueLabelsStayUnique() {
        List<String[]> scene = nodes(new String[] {"Search", ""}, new String[] {"", "Search"});
        assertEquals(Collections.singletonList(0), Targets.match(scene, "Search"));
    }

    @Test
    public void substringsOnlyApplyWhenNothingMatchesExactly() {
        List<String[]> scene = nodes(new String[] {"Save draft", ""}, new String[] {" save ", ""});
        assertEquals(Collections.singletonList(1), Targets.match(scene, "Save"));
        assertEquals(Collections.singletonList(0), Targets.match(scene, "draft"));
        assertEquals(
                Arrays.asList(0, 1),
                Targets.match(
                        nodes(new String[] {"Save draft", ""}, new String[] {"Save copy", ""}),
                        "save"));
    }

    @Test
    public void bracketsAreIgnoredLikeTheHostDisplaysThem() {
        List<String[]> scene = nodes(new String[] {"Inbox [3]", ""});
        assertEquals(Collections.singletonList(0), Targets.match(scene, "Inbox 3"));
        assertEquals("inbox 3", Targets.normalize("  Inbox  [3] "));
    }

    @Test
    public void emptyLabelsNeverMatch() {
        assertEquals(Collections.emptyList(), Targets.match(nodes(new String[] {"", ""}), ""));
        assertEquals(Collections.emptyList(), Targets.match(nodes(new String[] {"", null}), "x"));
    }

    @Test
    public void nonBreakingSpacesAndEllipsesMatchTheHost() {
        assertEquals("nom :", Targets.normalize("Nom\u00a0:"));
        assertEquals("nom :", Targets.normalize("Nom :"));
        assertEquals("loading", Targets.normalize("Loading\u2026"));
        List<String[]> scene = nodes(new String[] {"Ouvrir\u202f:", ""});
        assertEquals(Collections.singletonList(0), Targets.match(scene, "Ouvrir :"));
        assertEquals(
                Collections.singletonList(0),
                Targets.match(nodes(new String[] {"Save draft", ""}), "Save\u2026"));
    }

    @Test
    public void onlyUnicodeWhiteSpaceIsTrimmedLikeTheHost() {
        assertEquals("\u0007save", Targets.normalize("\u0007Save"));
        assertEquals("loading\u0001", Targets.normalize("Loading\u0001\u2026"));
    }
}
