/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.Failures;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.ui.RestartLedger;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import static com.onurkat.reclazz.spring.PropertyChangeCheck.call;

/** Replaces verified Boot file sources in place; never elevates them above command-line overrides. */
public final class SpringPropertyFiles {
    private static final String ORIGIN_SOURCE = "org.springframework.boot.env.OriginTrackedMapPropertySource";
    private static final String PROFILE = "spring.config.activate.on-profile";
    private static final Pattern DOCUMENT = Pattern.compile(".* \\(document #(\\d+)\\)$");
    // Values do not retain their Environment key. Empty sources need explicit ownership
    // because they have no remaining key from which Boot can return a resource origin.
    private final Map<Object, Map<Path, List<Owned>>> ownership = new WeakHashMap<>();
    private record LoggerWarnings(Map<String,Object> values, Set<String> messages) { }
    private final Map<Object,Map<Path,LoggerWarnings>> loggerWarnings=new WeakHashMap<>();
    private static final String LOGGER_SET_PENDING="logger level changes have not reached the logging backend";
    private final Map<Object,Map<Path,Map<String,Object>>> unappliedSets=new WeakHashMap<>();
    public interface LoggerAcceptance {
        void retry(Set<String> keys);
        void warn(String message);
        default void warn(String concern,String message){warn(message);}
        default void setConcern(Set<String> attempted,Set<String> unapplied) { }
        void resolved();
    }
    private record Owned(String name, int document, Object source, Map<String, Object> accepted) { }
    private record Prepared(Object context, Object live, Object candidate, Object before, Path file, List<Owned> old,
                            List<Owned> next, Map<String, String> changed, Set<String> removed,
                            List<SpringConfigImports.Plan> imports) { }
    public record LoggerOwner(Object applicationContext, Set<String> changedKeys, Set<String> removedKeys,
                              Map<String, String> previousLoggingValues, Map<String, String> effectiveLoggingValues, Runnable retryLoggerReset, String metadataFailure, LoggerAcceptance acceptance) {
        public LoggerOwner(Object applicationContext, Set<String> changedKeys, Set<String> removedKeys,
                           Map<String,String> previousLoggingValues, Map<String,String> effectiveLoggingValues, Runnable retryLoggerReset) {
            this(applicationContext,changedKeys,removedKeys,previousLoggingValues,effectiveLoggingValues,retryLoggerReset,null,new LoggerAcceptance() {
                public void retry(Set<String> keys){if(!keys.isEmpty())retryLoggerReset.run();}
                public void warn(String message){StatusReporter.warn(message);}
                public void resolved() { }
            });
        }
    }
    public record Result(PropertyChangeOutcome outcome, Map<String, String> effective, Set<String> changed,
                         List<LoggerOwner> loggerOwners) {
        public Result(PropertyChangeOutcome outcome, Map<String, String> effective, Set<String> changed) {
            this(outcome, effective, changed, List.of());
        }
    }

    /** Null means an unowned .properties file: the caller may retain its legacy path. */
    public synchronized Result apply(Path path, List<Object> contexts, Consumer<Runnable> boundary) {
        return apply(path, () -> contexts, boundary);
    }

    public synchronized Result apply(Path path, Supplier<List<Object>> contexts, Consumer<Runnable> boundary) {
        return apply(path, contexts, boundary, false);
    }

    public synchronized Result apply(Path path, Supplier<List<Object>> contexts, Consumer<Runnable> boundary,
                                     boolean awaitingOutput) {
        List<Prepared> plans = new ArrayList<>();
        Set<String> changed = new LinkedHashSet<>();
        Set<Path> loaded = new TreeSet<>();
        try {
            Path file = path.toFile().getCanonicalFile().toPath();
            byte[] bytes = Files.notExists(file) ? new byte[0] : Files.readAllBytes(file);
            boolean bootSources = false;
            for (Object context : contexts.get()) {
                if (Arrays.stream(context.getClass().getMethods()).noneMatch(m -> m.getName().equals("getEnvironment")
                        && m.getParameterCount() == 0)) continue;
                Object live = call(context, "getEnvironment");
                for (Object source : (Iterable<?>) call(live, "getPropertySources"))
                    if (source.getClass().getName().equals(ORIGIN_SOURCE)) bootSources = true;
                List<Owned> owned = owned(live, file);
                if (owned.isEmpty()) {
                    if (file.toString().endsWith(".properties")) loaded.addAll(loadedFilesWithName(live, file));
                    continue;
                }
                Prepared plan = prepare(context, live, file, bytes, owned);
                plans.add(plan); changed.addAll(plan.changed().keySet());
            }
            if (plans.isEmpty()) {
                if (!bootSources && path.toString().endsWith(".properties")) return null;
                if (awaitingOutput) return new Result(PropertyChangeOutcome.held(
                        PropertyChangeOutcome.State.NOT_RUN, List.of()), Map.of(), Set.of());
                String reason = file.toString().endsWith(".properties")
                        ? file + " is not the file this application loaded (loaded files with this name: " + loaded
                                + "), nothing was applied from this file."
                        : "No verified Boot source owns " + path.getFileName();
                return held(PropertyChangeOutcome.State.UNCHECKABLE, reason);
            }
            Map<Prepared, ValueSelection> selections = new IdentityHashMap<>();
            Map<Prepared, Map<String, Object>> resets = new IdentityHashMap<>();
            for (Prepared plan : plans) {
                Map<String, Object> reset = new HashMap<>(); resets.put(plan, reset);
                var check = new PropertyChangeCheck().check(List.of(plan.context()), plan.changed(), plan.candidate(),
                        plan.removed(), reset, plan.before());
                if (!check.passed()) return held(check.state(), String.join("; ", check.findings()));
                selections.put(plan, check.selection());
            }
            Map<String, String> effective = new LinkedHashMap<>();
            for (String key : changed) {
                Object value = call(plans.get(0).candidate(), "getProperty", key);
                if (value != null) effective.put(key, String.valueOf(value));
            }
            List<LoggerOwner> loggerOwners = new ArrayList<>();
            Map<Prepared,Set<String>> metadataHeld=new IdentityHashMap<>();
            for (Prepared plan : plans) {
                Set<String> loggingKeys=new LinkedHashSet<>(plan.changed().keySet());loggingKeys.addAll(plan.removed());
                loggingKeys.removeIf(key->!com.onurkat.reclazz.reload.LoggingReloader.isLevelKey(key));
                if(loggingKeys.isEmpty())continue;
                Map<String,Object> loggingValues=new LinkedHashMap<>();
                for(Owned next:plan.next())next.accepted().forEach((key,value)->{
                    if(com.onurkat.reclazz.reload.LoggingReloader.isLevelKey(key) || key.startsWith("logging.group."))loggingValues.put(key,value);
                });
                var warnings=loggerWarnings.computeIfAbsent(plan.live(),ignored->new HashMap<>());
                LoggerWarnings priorWarnings=warnings.get(plan.file());
                if(priorWarnings==null || !priorWarnings.values().equals(loggingValues))
                    warnings.put(plan.file(),new LoggerWarnings(Map.copyOf(loggingValues),new HashSet<>()));
                Map<String, String> previous = new LinkedHashMap<>(), current = new LinkedHashMap<>();
                Set<String> keys = new LinkedHashSet<>(plan.changed().keySet());
                for (Owned old : plan.old()) keys.addAll(old.accepted().keySet());
                String metadataFailure=null;
                try {
                for (String key : keys) {
                    if (!key.startsWith("logging.level.") && !key.startsWith("log4j2.logger.")
                            && !key.equals("log4j2.rootLogger.level")) continue;
                    Object before = call(plan.before(), "getProperty", key);
                    Object after = call(plan.candidate(), "getProperty", key);
                    if (before != null) previous.put(key, String.valueOf(before));
                    if (after != null) current.put(key, String.valueOf(after));
                }
                addLoggerGroups(plan.context(),plan.before(),previous);
                addLoggerGroups(plan.context(),plan.candidate(),current);
                } catch(Exception | LinkageError failure) {
                    metadataFailure=Failures.describe(PropertyChangeCheck.unwrap(failure));
                    metadataHeld.put(plan,loggingKeys);
                }
                loggerOwners.add(new LoggerOwner(plan.context(), Set.copyOf(plan.changed().keySet()), plan.removed(),
                        Map.copyOf(previous), Map.copyOf(current), () -> retryLoggerAcceptance(plan,loggingKeys), metadataFailure,new LoggerAcceptance() {
                            public void retry(Set<String> keys){retryLoggerAcceptance(plan,keys);}
                            public void warn(String message){
                                int separator=message.indexOf(':');warnLoggerOnce(plan,separator<0?message:message.substring(0,separator),message);
                            }
                            public void warn(String concern,String message){warnLoggerOnce(plan,concern,message);}
                            public void setConcern(Set<String> attempted,Set<String> unapplied){updateSetConcern(plan,attempted,unapplied);}
                            public void resolved(){clearLoggerWarnings(plan);}
                        }));
            }
            PropertyChangeOutcome[] outcome = {PropertyChangeOutcome.held(PropertyChangeOutcome.State.NOT_RUN,
                    List.of("Configuration file boundary did not run"))};
            boundary.accept(() -> {
                List<String> rebound = new ArrayList<>(), rebuilt = new ArrayList<>(), failures = new ArrayList<>();
                int fields = 0;
                try {
                    // Validate every carried selection before any live write.
                    try {
                        for (Prepared plan : plans)
                            new SpringPropertyRebinder(List.of(plan.context()))
                                    .validateSelection(plan.changed(), selections.get(plan));
                    } catch (Exception failure) {
                        outcome[0] = PropertyChangeOutcome.held(PropertyChangeOutcome.State.UNCHECKABLE,
                                List.of(Failures.describe(failure)));
                        return;
                    }
                    for (Prepared plan : plans) verifyOwnership(plan.live(), plan.old());
                    // Pools are matched to the keys they were built from, so read those before the sources change.
                    for (Prepared plan : plans) SpringDataSourceCheck.remember(plan.context(), plan.changed());
                    for (Prepared plan : plans) {
                        Object sources = call(plan.live(), "getPropertySources");
                        for (Owned next : plan.next()) call(sources, "replace", next.name(), next.source());
                        for (SpringConfigImports.Plan imports : plan.imports()) {
                            imports.apply(sources);
                            imports.committed();
                            // Imported files are found again through their resource origins on their next edit.
                            ownership.get(plan.live()).keySet().removeAll(imports.files());
                            // Files no longer imported are no longer owned: their next edit is not this environment's.
                            ownership.get(plan.live()).keySet().removeAll(imports.released());
                        }
                        // Keep the old accepted values until binding succeeds, so partial failures retry.
                        List<Owned> pending = new ArrayList<>();
                        for (int i = 0; i < plan.next().size(); i++) {
                            Owned next = plan.next().get(i);
                            pending.add(new Owned(next.name(), next.document(), next.source(), plan.old().get(i).accepted()));
                        }
                        ownership.get(plan.live()).put(plan.file(), List.copyOf(pending));
                    }
                    for (Prepared plan : plans) {
                        if (plan.changed().isEmpty()) continue;
                        var result = new SpringPropertyRebinder(List.of(plan.context()))
                                .applyLive(plan.changed(), resets.get(plan), false, selections.get(plan));
                        rebound.addAll(result.rebound()); rebuilt.addAll(result.rebuilt());
                        fields += result.valueFields(); failures.addAll(result.findings());
                        if (result.findings().isEmpty() && plan.changed().keySet().stream().anyMatch(k -> k.startsWith("spring.jackson."))) {
                            Object factory = SpringBeans.getBeanFactory(plan.context());
                            if (JacksonMapperRefresh.isBootJackson(factory) && !JacksonMapperRefresh.refresh(factory))
                                failures.add(JacksonMapperRefresh.RESTART);
                        }
                    }
                    if (failures.isEmpty())
                        for (Prepared plan : plans) {
                            ownership.get(plan.live()).put(plan.file(), plan.next());
                            if(metadataHeld.containsKey(plan))retryLoggerAcceptance(plan,metadataHeld.get(plan));
                            pruneSetConcerns(plan);
                        }
                } catch (Throwable failure) { failures.add(Failures.describe(failure)); }
                outcome[0] = new PropertyChangeOutcome(failures.isEmpty() ? PropertyChangeOutcome.State.APPLIED
                        : PropertyChangeOutcome.State.PARTIAL, rebound, fields, rebuilt, failures);
            });
            if (outcome[0].state() == PropertyChangeOutcome.State.PARTIAL)
                StatusReporter.warn("Property change partially applied; file remains pending: " + outcome[0].findings());
            return new Result(outcome[0], Map.copyOf(effective), Set.copyOf(changed), List.copyOf(loggerOwners));
        } catch (Throwable failure) {
            return held(PropertyChangeOutcome.State.UNCHECKABLE, Failures.describe(PropertyChangeCheck.unwrap(failure)));
        }
    }

    private static void addLoggerGroups(Object context, Object environment, Map<String,String> result) throws Exception {
        ClassLoader loader=context.getClass().getClassLoader();
        try {
            Class<?> listener=Class.forName("org.springframework.boot.context.logging.LoggingApplicationListener",false,loader);
            String bean=(String)listener.getField("LOGGER_GROUPS_BEAN_NAME").get(null);
            if ((boolean)call(context,"containsBean",bean)) {
                for(Object group:(Iterable<?>)call(context,"getBean",bean)) {
                    String name=(String)call(group,"getName");
                    @SuppressWarnings("unchecked") List<String> members=(List<String>)call(group,"getMembers");
                    result.put("logging.group."+name,String.join(",",members));
                }
            }
        } catch (ClassNotFoundException noBoot) { return; }
        Set<String> names=new LinkedHashSet<>();
        for(Object source:(Iterable<?>)call(environment,"getPropertySources")) {
            try {
                for(String key:(String[])call(source,"getPropertyNames"))if(key.startsWith("logging.group.")) {
                    String name=key.substring("logging.group.".length());int index=name.indexOf('[');
                    names.add(index<0?name:name.substring(0,index));
                }
            } catch(NoSuchMethodException notEnumerable) { }
        }
        for(String name:names) {
            String[] members=(String[])environment.getClass().getMethod("getProperty",String.class,Class.class)
                    .invoke(environment,"logging.group."+name,String[].class);
            if(members!=null)result.put("logging.group."+name,String.join(",",members));
        }
        Set<String> members=new LinkedHashSet<>();
        result.forEach((key,value)->{if(key.startsWith("logging.group."))for(String member:value.split(","))if(!member.isBlank())members.add(member.trim());});
        for(String member:members) {
            String key="logging.level."+member;
            Object value=call(environment,"getProperty",key);
            if(value!=null)result.put(key,String.valueOf(value));
        }
    }

    private synchronized void warnLoggerOnce(Prepared plan,String concern,String message) {
        LoggerWarnings state=loggerWarnings.getOrDefault(plan.live(),Map.of()).get(plan.file());
        if(state!=null && state.messages().add(concern))StatusReporter.warn(message);
    }
    private static Map<String,Object> proposedFileValues(Prepared plan) {
        Map<String,Object> values=new HashMap<>();for(Owned next:plan.next())values.putAll(next.accepted());return values;
    }
    private synchronized void updateSetConcern(Prepared plan,Set<String> attempted,Set<String> unapplied) {
        try {verifyOwnership(plan.live(),plan.next());}catch(Exception stale){return;}
        Map<Path,Map<String,Object>> files=unappliedSets.computeIfAbsent(plan.live(),ignored->new HashMap<>());
        Map<String,Object> values=new HashMap<>(files.getOrDefault(plan.file(),Map.of()));
        attempted.forEach(values::remove);
        Map<String,Object> proposed=proposedFileValues(plan);
        for(String key:unapplied)if(proposed.containsKey(key))values.put(key,proposed.get(key));
        if(values.isEmpty())files.remove(plan.file());else files.put(plan.file(),Map.copyOf(values));
        refreshSetLedger(plan.file());
    }
    private synchronized void pruneSetConcerns(Prepared plan) {
        Map<Path,Map<String,Object>> files=unappliedSets.get(plan.live());
        if(files!=null && files.containsKey(plan.file())) {
            Map<String,Object> proposed=proposedFileValues(plan),values=new HashMap<>(files.get(plan.file()));
            values.entrySet().removeIf(entry->!Objects.equals(proposed.get(entry.getKey()),entry.getValue()));
            if(values.isEmpty())files.remove(plan.file());else files.put(plan.file(),Map.copyOf(values));
        }
        refreshSetLedger(plan.file());
    }
    private void refreshSetLedger(Path file) {
        boolean pending=unappliedSets.values().stream().anyMatch(files->!files.getOrDefault(file,Map.of()).isEmpty());
        if(pending)RestartLedger.note(file.toString(),LOGGER_SET_PENDING);
        else RestartLedger.resolve(file.toString(),LOGGER_SET_PENDING);
    }
    private synchronized void clearLoggerWarnings(Prepared plan) {
        Map<Path,LoggerWarnings> states=loggerWarnings.get(plan.live());
        if(states!=null)states.remove(plan.file());
    }
    private synchronized void retryLoggerAcceptance(Prepared plan,Set<String> keys) {
        if(keys.isEmpty())return;
        List<Owned> current=ownership.getOrDefault(plan.live(),Map.of()).get(plan.file());
        if(current==null || current.size()!=plan.next().size())throw new IllegalStateException("logger file ownership changed");
        List<Owned> pending=new ArrayList<>();
        for(int i=0;i<current.size();i++) {
            Owned now=current.get(i),old=plan.old().get(i);
            if(now.source()!=plan.next().get(i).source())throw new IllegalStateException("logger source replaced before retry");
            Map<String,Object> accepted=new HashMap<>(now.accepted());
            accepted.keySet().removeAll(keys);
            old.accepted().forEach((key,value)->{if(keys.contains(key))accepted.put(key,value);});
            pending.add(new Owned(now.name(),now.document(),now.source(),Map.copyOf(accepted)));
        }
        ownership.get(plan.live()).put(plan.file(),List.copyOf(pending));
    }

    private static Result held(PropertyChangeOutcome.State state, String reason) {
        if (reason.toLowerCase(Locale.ROOT).contains("restart"))
            RestartLedger.note("Spring configuration", reason);
        StatusReporter.warn((state == PropertyChangeOutcome.State.REJECTED ? "Rejected" : "Uncheckable")
                + ": the running configuration is unchanged. " + reason);
        return new Result(PropertyChangeOutcome.held(state, List.of(reason)), Map.of(), Set.of());
    }

    private List<Owned> owned(Object environment, Path file) throws Exception {
        Map<Path, List<Owned>> files = ownership.computeIfAbsent(environment, ignored -> new HashMap<>());
        List<Owned> known = files.get(file);
        if (known != null) { verifyOwnership(environment, known); return known; }
        List<Owned> found = new ArrayList<>();
        for (Object source : (Iterable<?>) call(environment, "getPropertySources")) {
            if (!source.getClass().getName().equals(ORIGIN_SOURCE)) continue;
            Set<Path> origins = origins(source);
            if (origins.contains(file)) {
                if (origins.size() != 1) throw new IllegalStateException("Mixed resource origins in one property source");
                String name = (String) call(source, "getName");
                var match = DOCUMENT.matcher(name);
                found.add(new Owned(name, match.matches() ? Integer.parseInt(match.group(1)) : 0,
                        source, values(source)));
            }
        }
        if (!found.isEmpty()) files.put(file, List.copyOf(found));
        return List.copyOf(found);
    }

    // Information only: neither matching names nor these paths establish a copy relation.
    private Set<Path> loadedFilesWithName(Object environment, Path file) {
        Set<Path> loaded = new TreeSet<>();
        for (var entry : ownership.getOrDefault(environment, Map.of()).entrySet()) {
            if (!entry.getKey().getFileName().equals(file.getFileName())) continue;
            try {
                verifyOwnership(environment, entry.getValue());
                loaded.add(entry.getKey());
            } catch (Exception stale) { /* An externally replaced source is no longer verified. */ }
        }
        try {
            for (Object source : (Iterable<?>) call(environment, "getPropertySources")) {
                if (!source.getClass().getName().equals(ORIGIN_SOURCE)) continue;
                try {
                    Set<Path> paths = origins(source);
                    if (paths.size() == 1) {
                        Path loadedFile = paths.iterator().next();
                        if (loadedFile.getFileName().equals(file.getFileName())) loaded.add(loadedFile);
                    }
                } catch (Exception unavailable) { /* Optional diagnostic must not change the held outcome. */ }
            }
        } catch (Exception unavailable) { /* Keep the warning even if loaded paths cannot be described. */ }
        return loaded;
    }

    private static Set<Path> origins(Object source) throws Exception {
        Set<Path> origins = new HashSet<>();
        for (String key : (String[]) call(source, "getPropertyNames")) {
            Object origin = call(source, "getOrigin", key);
            if (origin == null || !origin.getClass().getName().equals("org.springframework.boot.origin.TextResourceOrigin")) continue;
            Object resource = call(origin, "getResource");
            try {
                java.io.File originFile = (java.io.File) call(resource, "getFile");
                origins.add(originFile.getCanonicalFile().toPath());
            } catch (java.lang.reflect.InvocationTargetException notFile) { /* Non-file resource cannot own this edit. */ }
        }
        return origins;
    }

    private static void verifyOwnership(Object environment, List<Owned> owned) throws Exception {
        Object sources = call(environment, "getPropertySources");
        for (Owned item : owned)
            if (call(sources, "get", item.name()) != item.source())
                throw new IllegalStateException("Property source was replaced externally: " + item.name());
    }

    private static Prepared prepare(Object context, Object live, Path file, byte[] bytes, List<Owned> owned) throws Exception {
        ClassLoader loader = context.getClass().getClassLoader();
        String parserName = file.toString().endsWith(".properties") ? "PropertiesPropertySourceLoader" : "YamlPropertySourceLoader";
        Object parser = Class.forName("org.springframework.boot.env." + parserName, true, loader).getConstructor().newInstance();
        Class<?> resourceType = Class.forName("org.springframework.core.io.Resource", true, loader);
        Object originalResource = Class.forName("org.springframework.core.io.FileSystemResource", true, loader)
                .getConstructor(java.io.File.class).newInstance(file.toFile());
        Object resource = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{resourceType}, (proxy, method, args) -> {
            if (method.getName().equals("getInputStream")) return new java.io.ByteArrayInputStream(bytes);
            if (method.getName().equals("exists") || method.getName().equals("isReadable")) return true;
            if (method.getName().equals("contentLength")) return (long) bytes.length;
            try { return method.invoke(originalResource, args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
        List<?> documents = (List<?>) call(parser, "load", "reclazz-file", resource);
        Map<Integer, Map<String, Object>> active = new LinkedHashMap<>();
        for (int i = 0; i < documents.size(); i++) {
            Map<String, Object> values = values(documents.get(i));
            for (String key : values.keySet())
                if ((key.startsWith("spring.config.") && !key.equals(PROFILE) && !SpringConfigImports.isImportKey(key))
                        || key.startsWith("spring.profiles"))
                    throw new IllegalStateException("Config locations and profile changes require a restart: " + key);
            Object profile = values.get(PROFILE);
            if (profile != null) {
                String expression = String.valueOf(profile);
                if (expression.contains("${")) throw new IllegalStateException("Profile placeholders require a restart");
                Class<?> profiles = Class.forName("org.springframework.core.env.Profiles", true, loader);
                Object selector = profiles.getMethod("of", String[].class).invoke(null, (Object) new String[]{expression});
                if (!(boolean) call(live, "acceptsProfiles", selector)) continue;
            }
            active.put(i, values);
        }
        if (documents.isEmpty()) active.put(0, Map.of());
        Set<Integer> slots = new HashSet<>();
        for (Owned item : owned) slots.add(item.document());
        if (slots.size() != owned.size() || !slots.equals(active.keySet()))
            throw new IllegalStateException("Active configuration document topology changed; restart required");
        List<Owned> next = new ArrayList<>();
        Map<String, String> changed = new LinkedHashMap<>();
        Set<String> removed = new HashSet<>();
        for (Owned old : owned) {
            Map<String, Object> replacement = active.get(old.document());
            if (!Objects.equals(old.accepted().get(PROFILE), replacement.get(PROFILE)))
                throw new IllegalStateException("Configuration document activation changed; restart required");
            Set<String> keys = new HashSet<>(old.accepted().keySet()); keys.addAll(replacement.keySet());
            for (String key : keys) if (!Objects.equals(old.accepted().get(key), replacement.get(key))) {
                changed.put(key, String.valueOf(replacement.getOrDefault(key, "")));
                if (!replacement.containsKey(key)) removed.add(key);
            }
            Object source = Class.forName(ORIGIN_SOURCE, true, loader).getConstructor(String.class, Map.class)
                    .newInstance(old.name(), replacement);
            next.add(new Owned(old.name(), old.document(), source, replacement));
        }
        Object candidate = PropertyChangeCheck.candidateEnvironment(loader, live, Map.of());
        Object before = PropertyChangeCheck.candidateEnvironment(loader, live, Map.of());
        Object beforeSources = call(before, "getPropertySources");
        // Partial application retains the last accepted map. Retry the same
        // native before/after decision even when live sources already changed.
        for (Owned old : owned) {
            Object accepted = Class.forName(ORIGIN_SOURCE, true, loader).getConstructor(String.class, Map.class)
                    .newInstance(old.name(), old.accepted());
            call(beforeSources, "replace", old.name(), accepted);
        }
        Object sources = call(candidate, "getPropertySources");
        // candidateEnvironment's empty legacy overlay does not change file precedence.
        for (Owned item : next) call(sources, "replace", item.name(), item.source());
        // R38: an edited import list. The import keys themselves are not application values.
        changed.keySet().removeIf(SpringConfigImports::isImportKey);
        removed.removeIf(SpringConfigImports::isImportKey);
        // Each document's imports sit directly above that document (Boot), so each changed document gets its own plan.
        List<SpringConfigImports.Plan> imports = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Owned old : owned) {
            // What the live environment reflects: a save whose other keys failed to bind still applied its imports.
            List<String> oldImports = SpringConfigImports.liveEntries(live, old.name(), SpringConfigImports.entries(old.accepted()));
            List<String> newImports = SpringConfigImports.entries(active.get(old.document()));
            if (oldImports.equals(newImports)) continue;
            SpringConfigImports.Plan plan = SpringConfigImports.plan(loader, live, file, old.name(), oldImports, newImports);
            plan.apply(sources);
            imports.add(plan);
            keys.addAll(plan.before().keySet()); keys.addAll(plan.after().keySet());
        }
        if (!imports.isEmpty()) {
            for (String key : keys) {
                Object was = call(before, "getProperty", key), now = call(candidate, "getProperty", key);
                if (Objects.equals(was == null ? null : String.valueOf(was), now == null ? null : String.valueOf(now))) continue;
                changed.put(key, now == null ? "" : String.valueOf(now));
                if (now == null) removed.add(key);
            }
        }
        return new Prepared(context, live, candidate, before, file, owned, List.copyOf(next), Map.copyOf(changed),
                Set.copyOf(removed), List.copyOf(imports));
    }

    private static Map<String, Object> values(Object source) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : (String[]) call(source, "getPropertyNames")) {
            Object value = call(source, "getProperty", key);
            if (value != null) result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }
}
