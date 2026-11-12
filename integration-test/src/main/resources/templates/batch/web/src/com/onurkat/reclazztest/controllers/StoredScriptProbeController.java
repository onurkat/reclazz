package com.onurkat.reclazztest.controllers;

import de.hybris.platform.scripting.engine.ScriptingLanguagesService;
import de.hybris.platform.scripting.engine.ScriptExecutable;
import de.hybris.platform.scripting.enums.ScriptType;
import de.hybris.platform.scripting.model.ScriptModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Owned model:// executions; never clears a native cache or evaluates inline HTTP content. */
@RestController
@RequestMapping("/test/batch/stored-script")
public class StoredScriptProbeController {
    @Autowired private ScriptingLanguagesService scriptingLanguagesService;
    @Autowired private ModelService modelService;
    @Autowired private FlexibleSearchService searchService;
    private final Map<String, Run> runs = new HashMap<>();
    private static class Run {
        final Set<String> admitted = new HashSet<>();
        ScriptExecutable target, control;
        boolean warmExecutableSame;
    }
    private String code(String run, String role) {
        if (!run.matches("[a-z0-9-]{1,60}")) throw new IllegalArgumentException("Invalid owned run");
        return "reclazz-script-" + run + "-" + role;
    }
    private String content(String version) {
        if (!Set.of("v1", "v2", "control").contains(version)) throw new IllegalArgumentException("Invalid version");
        return "return '" + (version.equals("control") ? "control:" : "script-" + version + ":") + "' + nonce";
    }
    private List<ScriptModel> rows(String run, String role) {
        var q = new FlexibleSearchQuery("SELECT {pk} FROM {Script} WHERE {code}=?code");
        q.addQueryParameter("code", code(run, role)); q.setDisableSearchRestrictions(true);
        return searchService.<ScriptModel>search(q).getResult();
    }
    private Run owned(String run) {
        code(run, "target"); Run value = runs.get(run);
        if (value == null) throw new IllegalStateException("Run not admitted");
        return value;
    }
    private ScriptExecutable resolve(String run, String role) {
        return scriptingLanguagesService.getExecutableByURI("model://" + code(run, role));
    }
    private Map<String, Object> row(String run, String role) {
        var all = rows(run, role);
        var active = all.stream().filter(s -> Boolean.TRUE.equals(s.getActive())).toList();
        if (active.size() != 1) throw new IllegalStateException("Expected one active owned script");
        var script = active.get(0); modelService.refresh(script);
        return Map.of("code", script.getCode(), "pk", script.getPk().toString(),
            "content", script.getContent(), "checksum", script.getChecksum(), "version", script.getVersion(),
            "revisions", all.stream().map(s -> s.getPk().toString()).sorted().toList());
    }
    private String execute(ScriptExecutable executable, String input) {
        var result = executable.execute(Map.of("nonce", input));
        if (!result.isSuccessful()) throw new IllegalStateException("Native script execution failed");
        if (!(result.getScriptResult() instanceof String)) throw new IllegalStateException("Expected native String result");
        return (String) result.getScriptResult();
    }
    @PostMapping("/begin")
    public synchronized Map<String, Object> begin(@RequestParam("run") String run) {
        code(run, "target");
        if (runs.containsKey(run) || !rows(run, "target").isEmpty() || !rows(run, "control").isEmpty())
            throw new IllegalStateException("Owned code collision before admission");
        var value = new Run(); runs.put(run, value);
        for (String role : List.of("target", "control")) {
            value.admitted.add(role);
            ScriptModel script = modelService.create(ScriptModel.class);
            script.setCode(code(run, role)); script.setScriptType(ScriptType.GROOVY);
            script.setDisabled(false); script.setContent(content(role.equals("target") ? "v1" : "control"));
            modelService.save(script);
        }
        value.target = resolve(run, "target"); value.control = resolve(run, "control");
        value.warmExecutableSame = value.target == resolve(run, "target");
        return read(run);
    }
    @GetMapping("/read")
    public synchronized Map<String, Object> read(@RequestParam("run") String run) {
        var value = owned(run);
        return Map.of("run", run, "uri", "model://" + code(run, "target"),
            "target", row(run, "target"), "control", row(run, "control"),
            "serviceIdentity", System.identityHashCode(scriptingLanguagesService),
            "jvmStart", ManagementFactory.getRuntimeMXBean().getStartTime(),
            "warmExecutableSame", value.warmExecutableSame, "warmResolutions", 2, "retainedIdentity", System.identityHashCode(value.target));
    }
    @PostMapping("/execute")
    public synchronized Map<String, Object> execute(@RequestParam("run") String run, @RequestParam("input") String input) {
        var value = owned(run);
        if (!input.startsWith(run + ":") || input.length() > 120) throw new IllegalArgumentException("Wrong run binding");
        var fresh = resolve(run, "target"); var control = resolve(run, "control");
        var result = new LinkedHashMap<String, Object>(read(run));
        result.put("input", input); result.put("freshResult", execute(fresh, input));
        result.put("retainedResult", execute(value.target, input));
        result.put("controlResult", execute(control, input));
        result.put("retainedControlResult", execute(value.control, input));
        result.put("freshIdentity", System.identityHashCode(fresh));
        result.put("controlIdentity", System.identityHashCode(control));
        return result;
    }
    @GetMapping(value="/script", produces="text/plain")
    public synchronized String script(@RequestParam("run") String run, @RequestParam("version") String version) {
        owned(run);
        if (!Set.of("v1", "v2").contains(version)) throw new IllegalArgumentException("Invalid edit version");
        return "UPDATE Script;code[unique=true];active[unique=true];content\n;" + code(run, "target")
            + ";true;" + content(version) + "\n";
    }
    @PostMapping("/cleanup")
    public synchronized Map<String, Object> cleanup(@RequestParam("run") String run) {
        code(run, "target"); Run value = runs.get(run);
        if (value == null) return Map.of("run", run, "admitted", false, "ownedRemaining", 0);
        for (String role : value.admitted) {
            var owned = rows(run, role);
            for (var script : owned) {
                if (!Set.of(content("v1"), content("v2"), content("control")).contains(script.getContent())
                        || !ScriptType.GROOVY.equals(script.getScriptType()))
                    throw new IllegalStateException("Unexpected content in owned revision");
            }
            modelService.removeAll(owned);
        }
        long remaining = value.admitted.stream().mapToLong(role -> rows(run, role).size()).sum();
        if (remaining == 0) runs.remove(run);
        return Map.of("run", run, "admitted", true, "ownedRemaining", remaining);
    }
}
