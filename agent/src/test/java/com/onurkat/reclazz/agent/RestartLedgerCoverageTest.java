/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.AgentSources;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Everything that tells the developer to restart has to be answerable later.
 *
 * <p>Reclazz says what a restart would change at the moment it happens, once,
 * in a log that keeps moving, and {@link RestartLedger} is what makes that
 * answerable an hour afterwards when the developer asks whether they still need
 * to restart. So a warning that mentions a restart and does not reach the
 * ledger is worse than a missing feature: the question gets an answer that
 * reads as complete and is not, and the developer trusts it.
 *
 * <p>That drift is what this catches. It went unnoticed until it was looked
 * for, and eight warnings had it: a new entity with no persistence unit to map
 * into, a class loaded before instrumentation, a Hibernate cache that could not
 * be invalidated, a bean holding a property value it read at startup, a bean
 * added from XML that nothing has re-injected, and the metaspace warning added
 * two rounds ago.
 *
 * <p>A source scan rather than a runtime one, because the situations are
 * mutually exclusive, most need a database or a framework to reach, and no
 * suite reaches them all in one run. What can be checked everywhere at once is
 * the pairing itself.
 */
class RestartLedgerCoverageTest {

    /**
     * For a warning that mentions a restart and genuinely is not one to
     * remember: put this on it, and say why in the same breath.
     */
    private static final String OPT_OUT = "ledger-exempt";

    private static final Pattern WARN = Pattern.compile("StatusReporter\\s*\\.\\s*warn\\s*\\(");

    private static final Pattern RESTART = Pattern.compile("restart", Pattern.CASE_INSENSITIVE);

    /**
     * How far from the warning its note may sit. Tight on purpose, and the
     * first version was not: at twenty-five lines either way, a warning was
     * counted as remembered because an unrelated note happened to be in the
     * same region of the file, and a deliberately broken one passed. The
     * convention in this codebase is that the note follows the warning it
     * belongs to, within a few lines, so that is what is checked.
     */
    private static final int LOOK_BEHIND = 6;

    private static final int LOOK_AHEAD = 12;

    @Test
    void everyWarningThatMentionsARestartIsRemembered() throws IOException {
        List<String> unremembered = new ArrayList<>();
        for (Path file : AgentSources.javaFiles()) {
            if (file.getFileName().toString().equals("RestartLedger.java")) continue;
            unremembered.addAll(scan(file));
        }

        assertEquals(List.of(), unremembered,
                "these tell the developer a restart is needed and never reach the ledger, "
                        + "so \"do I still need to restart?\" answers without them");
    }

    private static List<String> scan(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        List<String> found = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            if (!WARN.matcher(lines.get(i)).find()) continue;

            StringBuilder statement = new StringBuilder();
            int end = i;
            while (end < lines.size() && end < i + 30) {
                statement.append(lines.get(end)).append(' ');
                if (lines.get(end).stripTrailing().endsWith(");")) break;
                end++;
            }
            String text = statement.toString();
            if (!RESTART.matcher(text).find()) continue;
            if (text.contains(OPT_OUT)) continue;

            String window = String.join(" ", lines.subList(
                    Math.max(0, i - LOOK_BEHIND), Math.min(lines.size(), end + LOOK_AHEAD)));
            if (!window.contains("RestartLedger.note")) {
                found.add(file.getFileName() + ":" + (i + 1));
            }
            i = end;
        }
        return found;
    }

}
