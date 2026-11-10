/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.util.regex.Pattern;

/** Per-event descriptions only. No receipt, neighboring-event or source-owner inference. */
final class EventObservation {
    private static final Pattern COMPILED = Pattern.compile("^Compiled (.+\\.java)(?: \\([0-9]+ms\\))?$");
    private static final Pattern DIAGNOSTIC = Pattern.compile("^  (.+\\.java):[1-9][0-9]*: .+$", Pattern.DOTALL);

    static boolean fileBasename(String file) {
        return file != null && file.length() > 5 && file.length() <= 255 && file.endsWith(".java")
                && !file.contains("/") && !file.contains("\\") && file.codePoints().noneMatch(Character::isISOControl);
    }

    static JsonObject describe(EventSubscription.Event event) {
        JsonObject out = new JsonObject();
        String message = event.message(), level = event.level(), type = "unknown";
        String owner = null, file = null;
        if (level.equals("COMPILE")) {
            var match = COMPILED.matcher(message);
            if (match.matches() && fileBasename(match.group(1)) && AwaitReload.compile(message, match.group(1))) {
                file = match.group(1); type = "compile";
            }
        } else if (level.equals("RELOAD") || level.equals("STRUCTURAL_RELOAD")) {
            String prefix = level.equals("RELOAD") ? "Reloaded " : "Structural reload: ";
            if (message.startsWith(prefix)) {
                String tail = message.substring(prefix.length());
                int end = 0; while (end < tail.length() && tail.charAt(end) != ' ' && tail.charAt(end) != ':') end++;
                String candidate = tail.substring(0, end);
                if (BuildSession.validClassName(candidate) && AwaitReload.reload(level, message, candidate)) {
                    owner = candidate; type = level.equals("RELOAD") ? "reload" : "structural_reload";
                }
            }
        } else if (level.equals("ERROR")) {
            if (message.equals("Compilation failed:")) type = "compile_error";
            else {
                var diagnostic = DIAGNOSTIC.matcher(message);
                if (diagnostic.matches() && fileBasename(diagnostic.group(1))) {
                    file = diagnostic.group(1); type = "compiler_diagnostic";
                } else {
                    for (String prefix : new String[]{"Hot-swap failed for ", "Failed to refresh Spring bean for "}) {
                        if (!message.startsWith(prefix)) continue;
                        int colon = message.indexOf(':', prefix.length());
                        if (colon < 0) continue;
                        String candidate = message.substring(prefix.length(), colon);
                        if (BuildSession.validClassName(candidate)) {
                            owner = candidate; type = prefix.startsWith("Hot-swap") ? "refused" : "framework_failure";
                        }
                    }
                }
            }
        } else if (level.equals("WARN")) {
            String marker = ": superclass change not applied; restart required. Still extends ";
            int index = message.indexOf(marker);
            if (index > 0 && BuildSession.validClassName(message.substring(0, index))) {
                owner = message.substring(0, index); type = "restart_required";
            }
        }
        out.addProperty("sequence", Long.toString(event.sequence()));
        out.addProperty("level", level);
        out.add("timestamp", event.timestamp() == null ? JsonNull.INSTANCE : new JsonPrimitive(event.timestamp()));
        out.addProperty("type", type);
        out.addProperty("message", message.substring(0, Math.min(512, message.length())));
        out.addProperty("messageTruncated", message.length() > 512);
        if (owner != null) out.addProperty("ownerClassName", owner);
        if (file != null) out.addProperty("fileBasename", file);
        return out;
    }
    private EventObservation() { }
}
