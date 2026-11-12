package com.onurkat.reclazztest.controllers;

import de.hybris.platform.commons.enums.RendererTypeEnum;
import de.hybris.platform.commons.model.renderer.RendererTemplateModel;
import de.hybris.platform.commons.renderer.RendererService;
import de.hybris.platform.core.model.media.MediaModel;
import de.hybris.platform.servicelayer.i18n.I18NService;
import de.hybris.platform.servicelayer.media.MediaService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.session.SessionExecutionBody;
import de.hybris.platform.servicelayer.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Native database template rendering only; no cache clearing or email delivery. */
@RestController
@RequestMapping("/test/batch/velocity-template")
public class VelocityTemplateProbeController {
    @Autowired private RendererService rendererService;
    @Autowired private ModelService modelService;
    @Autowired private MediaService mediaService;
    @Autowired private FlexibleSearchService searchService;
    @Autowired private SessionService sessionService;
    @Autowired private I18NService i18NService;
    private static final List<String> ROLES = List.of("subject", "body", "control");
    private static final String NAME = "Örnek <&>";
    private final Map<String, Set<String>> runs = new HashMap<>();
    private String code(String run, String role) {
        if (!run.matches("[a-z0-9-]{1,60}") || !ROLES.contains(role)) throw new IllegalArgumentException("Invalid owned run/role");
        return "reclazz-velocity-" + run + "-" + role;
    }
    private String text(String role, String version) {
        if (!Set.of("v1", "v2").contains(version)) throw new IllegalArgumentException("Invalid version");
        return (role.equals("control") ? "Control" : (role.equals("subject") ? "Subject-" : "Body-") + version)
            + " $ctx.name | $ctx.nonce";
    }
    private <T> List<T> rows(String type, String value) {
        var q = new FlexibleSearchQuery("SELECT {pk} FROM {" + type + "} WHERE {code}=?code");
        q.addQueryParameter("code", value); q.setDisableSearchRestrictions(true);
        return searchService.<T>search(q).getResult();
    }
    private void owned(String run) {
        code(run, "subject");
        if (!runs.containsKey(run)) throw new IllegalStateException("Run not admitted");
    }
    private RendererTemplateModel template(String run, String role) {
        var value = rendererService.getRendererTemplateForCode(code(run, role));
        modelService.refresh(value);
        if (!RendererTypeEnum.VELOCITY.equals(value.getRendererType()) || !"java.util.Map".equals(value.getContextClass()))
            throw new IllegalStateException("Unexpected owned template type");
        return value;
    }
    private Map<String, Object> row(String run, String role) {
        var value = template(run, role); var media = value.getDefaultContent();
        if (media == null || !(code(run, role) + "_defaultTemplateScript").equals(media.getCode()))
            throw new IllegalStateException("Unexpected owned media");
        return Map.of("code", value.getCode(), "pk", value.getPk().toString(),
            "mediaCode", media.getCode(), "mediaPk", media.getPk().toString(),
            "content", value.getDefaultTemplateScript());
    }
    private Map<String, Object> renderLocal(String run, String input) {
        return sessionService.executeInLocalView(new SessionExecutionBody() {
            @Override public Object execute() {
                i18NService.setCurrentLocale(Locale.ENGLISH);
                var result = new LinkedHashMap<String, Object>(read(run));
                result.put("input", input); result.put("locale", i18NService.getCurrentLocale().toLanguageTag());
                for (String role : ROLES) {
                    var output = new StringWriter();
                    rendererService.render(template(run, role), Map.of("name", NAME, "nonce", input), output);
                    result.put(role + "Result", output.toString());
                }
                return result;
            }
        });
    }
    @PostMapping("/begin")
    public synchronized Map<String, Object> begin(@RequestParam("run") String run) {
        code(run, "subject");
        if (runs.containsKey(run)) throw new IllegalStateException("Owned run collision");
        for (String role : ROLES)
            if (!rows("RendererTemplate", code(run, role)).isEmpty() || !rows("Media", code(run, role) + "_defaultTemplateScript").isEmpty())
                throw new IllegalStateException("Owned code collision before admission");
        var admitted = new HashSet<String>(); runs.put(run, admitted);
        for (String role : ROLES) {
            admitted.add(role);
            RendererTemplateModel value = modelService.create(RendererTemplateModel.class);
            value.setCode(code(run, role)); value.setRendererType(RendererTypeEnum.VELOCITY);
            value.setContextClass("java.util.Map"); value.setDefaultTemplateScript(text(role, "v1"));
            modelService.save(value);
        }
        return renderLocal(run, run + ":warm");
    }
    @GetMapping("/read")
    public synchronized Map<String, Object> read(@RequestParam("run") String run) {
        owned(run);
        return Map.of("run", run, "subject", row(run, "subject"), "body", row(run, "body"), "control", row(run, "control"),
            "serviceIdentity", System.identityHashCode(rendererService), "jvmStart", ManagementFactory.getRuntimeMXBean().getStartTime());
    }
    @PostMapping("/render")
    public synchronized Map<String, Object> render(@RequestParam("run") String run, @RequestParam("input") String input) {
        owned(run);
        if (!input.startsWith(run + ":") || input.length() > 120) throw new IllegalArgumentException("Wrong run binding");
        return renderLocal(run, input);
    }
    @GetMapping(value="/script", produces="text/plain")
    public synchronized String script(@RequestParam("run") String run, @RequestParam("version") String version) {
        owned(run);
        return "UPDATE RendererTemplate;code[unique=true];defaultTemplateScript\n;" + code(run, "subject") + ";" + text("subject", version)
            + "\n;" + code(run, "body") + ";" + text("body", version) + "\n";
    }
    @PostMapping("/cleanup")
    public synchronized Map<String, Object> cleanup(@RequestParam("run") String run) {
        code(run, "subject"); var admitted = runs.get(run);
        if (admitted == null) return Map.of("run", run, "admitted", false, "ownedTemplates", 0, "ownedMedia", 0, "ownedRemaining", 0);
        for (String role : admitted) {
            var templates = this.<RendererTemplateModel>rows("RendererTemplate", code(run, role));
            for (var value : templates) {
                if (!RendererTypeEnum.VELOCITY.equals(value.getRendererType()) || !"java.util.Map".equals(value.getContextClass()))
                    throw new IllegalStateException("Unexpected owned template");
                var media = value.getDefaultContent();
                if (media != null && !(code(run, role) + "_defaultTemplateScript").equals(media.getCode()))
                    throw new IllegalStateException("Foreign default media attached");
            }
            // The exact media code was absent before admitting this role, including partial saves.
            for (var media : this.<MediaModel>rows("Media", code(run, role) + "_defaultTemplateScript"))
                if (mediaService.hasData(media)) mediaService.removeDataFromMedia(media);
            modelService.removeAll(templates);
            modelService.removeAll(this.<MediaModel>rows("Media", code(run, role) + "_defaultTemplateScript"));
        }
        long templates = admitted.stream().mapToLong(role -> rows("RendererTemplate", code(run, role)).size()).sum();
        long media = admitted.stream().mapToLong(role -> rows("Media", code(run, role) + "_defaultTemplateScript").size()).sum();
        if (templates + media == 0) runs.remove(run);
        return Map.of("run", run, "admitted", true, "ownedTemplates", templates, "ownedMedia", media, "ownedRemaining", templates + media);
    }
}
