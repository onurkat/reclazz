/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.platform;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure property-key parsing shared by core logging and platform configuration reload. */
public final class LoggingPropertyKeys {
    private static final String ROOT = "ROOT";
    private LoggingPropertyKeys() { }

    public static boolean isLevelKey(String key) {
        return key.startsWith("logging.level.") || key.startsWith("log4j2.logger.")
                && !key.contains(".appenderRef.") && (key.endsWith(".name") || key.endsWith(".level")) || key.equals("log4j2.rootLogger.level");
    }
    public static java.util.Set<String> resetKeys(Map<String,String> previous, Collection<String> removed,
                                                 Collection<String> changed, Map<String,String> current) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for(String key:removed)if(isLevelKey(key))keys.add(key);
        for(String key:changed)if(key.startsWith("log4j2.logger.") && key.endsWith(".name")
                && previous.get(key)!=null && !java.util.Objects.equals(previous.get(key),current.get(key)))keys.add(key);
        return keys;
    }
    public static java.util.Set<String> resetLoggerNames(Map<String,String> previous, Collection<String> keys) {
        java.util.Set<String> names=new java.util.LinkedHashSet<>();
        for(String key:keys) {
            if(key.startsWith("logging.level.") && key.length() > "logging.level.".length() && previous.containsKey(key))names.addAll(bootNames(key.substring("logging.level.".length()), previous));
            else if(key.equals("log4j2.rootLogger.level") && previous.containsKey(key))names.add(ROOT);
            else if(key.startsWith("log4j2.logger.") && (key.endsWith(".name") || key.endsWith(".level"))) {
                String name=previous.get(key.substring(0,key.lastIndexOf('.'))+".name");
                if(name!=null && !name.isBlank())names.add(name.trim());
            }
        }
        return names;
    }
    private static java.util.Set<String> bootNames(String name, Map<String,String> values) {
        String members = values.get("logging.group." + name);
        if (members == null) return java.util.Set.of(bootName(name));
        var result = new java.util.LinkedHashSet<String>();
        for (String member : members.split(",")) if (!member.isBlank()) result.add(bootName(member.trim()));
        return result;
    }
    private static String bootName(String name) {
        return name == null || name.isEmpty() || name.equalsIgnoreCase("root") ? ROOT : name;
    }

    public static Map<String, String> levelsIn(java.util.Properties properties,
                                               java.util.Collection<String> changedKeys) {
        Map<String, String> levels = new LinkedHashMap<>();

        for (String key : changedKeys) {
            if (!key.startsWith("logging.level.")) continue;
            String logger = key.substring("logging.level.".length());
            String level = properties.getProperty(key);
            if (!logger.isEmpty() && level != null) {
                String group=properties.getProperty("logging.group."+logger);
                if(group==null)levels.put(bootName(logger), level.trim());
                else for(String member:group.split(","))if(!member.isBlank())levels.put(bootName(member.trim()),level.trim());
            }
        }

        for (String key : changedKeys) {
            if (!key.startsWith("log4j2.logger.")) continue;

            String stem;
            if (key.endsWith(".level")) {
                stem = key.substring(0, key.length() - ".level".length());
            } else if (key.endsWith(".name")) {
                stem = key.substring(0, key.length() - ".name".length());
            } else {
                continue;
            }

            String logger = properties.getProperty(stem + ".name");
            String level = properties.getProperty(stem + ".level");
            if (logger != null && !logger.isBlank() && level != null && !level.isBlank()) {
                levels.put(logger.trim(), level.trim());
            }
        }
        if (changedKeys.contains("log4j2.rootLogger.level") && properties.getProperty("log4j2.rootLogger.level") != null)
            levels.put(ROOT, properties.getProperty("log4j2.rootLogger.level").trim());
        return levels;
    }

}
