package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.XmlProbe;
import com.onurkat.reclazztest.batch.XmlHolder;
import com.onurkat.reclazztest.batch.ValidationFixture;
import de.hybris.platform.core.model.user.TitleModel;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.processengine.definition.ProcessDefinitionResource;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.Resource;
import de.hybris.platform.util.Config;
import de.hybris.platform.util.localization.Localization;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/test/batch")
public class BatchVerificationController {
    @Resource(name = "reclazzBatchValidation") private ValidationFixture validation;
    @Autowired private FlexibleSearchService searchService;
    @GetMapping("/validation")
    public Map<String, Object> validation(@RequestParam("value") String value) { return validation.validate(value); }
    @GetMapping("/gates")
    public Map<String, Boolean> gates() throws ReflectiveOperationException {
        Class<?> agent = Class.forName("com.onurkat.reclazz.agent.ReclazzAgent", false, ClassLoader.getSystemClassLoader());
        Object config = agent.getMethod("getConfig").invoke(null);
        var result = new LinkedHashMap<String, Boolean>();
        result.put("autoUpdateRunningSystem", (Boolean) config.getClass().getMethod("isAutoUpdateRunningSystem").invoke(config));
        result.put("impexAllowRemove", (Boolean) config.getClass().getMethod("isImpexAllowRemove").invoke(config));
        // Identify this agent's exact code source; never expose command-line arguments.
        String prefix;
        try { prefix = "-javaagent:" + java.nio.file.Path.of(agent.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (java.net.URISyntaxException e) { throw new IllegalStateException(e); }
        final String expected = prefix;
        var arguments = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(value -> value.equals(expected) || value.startsWith(expected + "=")).toList();
        boolean found = arguments.size() == 1;
        result.put("agentArgsFound", found);
        Object launched = found ? config.getClass().getMethod("parse", String.class).invoke(null,
                arguments.get(0).length() == expected.length() ? "" : arguments.get(0).substring(expected.length() + 1)) : null;
        boolean matches = found;
        for (String option : List.of("resetStatics", "autoSolrReindex", "impexAllowRemove", "autoUpdateRunningSystem", "autoCompile")) {
            String getter = "is" + Character.toUpperCase(option.charAt(0)) + option.substring(1);
            boolean liveValue = (Boolean) config.getClass().getMethod(getter).invoke(config);
            boolean launchedValue = found && (Boolean) config.getClass().getMethod(getter).invoke(launched);
            result.put(option.equals("autoUpdateRunningSystem") ? "launchedAutoUpdateRunningSystem" :
                    option.equals("impexAllowRemove") ? "launchedImpexAllowRemove" : option, launchedValue);
            matches &= found && liveValue == launchedValue;
        }
        result.put("agentOptionsMatchConfig", matches);
        var field = agent.getDeclaredField("platformContext"); field.setAccessible(true);
        Object platform = field.get(null);
        var resources = (Map<?, ?>) platform.getClass().getMethod("getResourceDirs").invoke(platform);
        var generated = (Map<?, ?>) platform.getClass().getMethod("getGeneratedClassDirs").invoke(platform);
        result.put("codegenExtensionLoaded", resources.containsKey("reclazztest"));
        Object roots = generated.get("__platform_bootstrap__");
        result.put("codegenGeneratedRoots", roots instanceof List<?> && !((List<?>) roots).isEmpty());
        return result;
    }
    @GetMapping("/resource-owners")
    public List<Map<String, Object>> resourceOwners(@RequestParam("file") String filename) {
        if (!List.of("reclazztest-batch-spring.xml", "reclazztest-process-resources-spring.xml",
                "reclazztestocc-web-spring.xml").contains(filename)) throw new IllegalArgumentException("Not a synthetic XML file");
        var rows = new ArrayList<Map<String, Object>>();
        var owners = FixtureOwners.of(filename);
        for (int i = 0; i < owners.size(); i++) {
            var owner = owners.get(i); int ordinal = i + 1;
            rows.add(FixtureOwners.execute(owner, () -> {
                var row = new LinkedHashMap<String, Object>(); row.put("owner", ordinal);
                if (filename.equals("reclazztestocc-web-spring.xml")) {
                    Object mapping = owner.getBean("reclazzProbeWsDTOFieldSetLevelMapping");
                    var levels = (Map<?, ?>) mapping.getClass().getMethod("getLevelMapping").invoke(mapping);
                    row.put("basic", levels.get("BASIC"));
                }
                if (filename.equals("reclazztest-process-resources-spring.xml")) {
                    var resource = owner.getBean("reclazzBatchExistingProcessResource", ProcessDefinitionResource.class).getResource();
                    row.put("processBaseUri", resource.getURI().resolve(".").toString());
                    // Anonymous path-identity evidence, never register or repair a resource here.
                    ClassLoader loader = ClassLoader.getSystemClassLoader();
                    Class<?> agent = Class.forName("com.onurkat.reclazz.agent.ReclazzAgent", false, loader);
                    var field = agent.getDeclaredField("platformContext"); field.setAccessible(true);
                    Object platform = field.get(null);
                    var roots = (Map<String, List<java.nio.file.Path>>) platform.getClass().getMethod("getResourceDirs").invoke(platform);
                    java.nio.file.Path observed = java.nio.file.Path.of(resource.getURI());
                    java.nio.file.Path advertised = roots.get("reclazztest").get(0).resolve("process").resolve(resource.getFilename());
                    Class<?> registrations = Class.forName("com.onurkat.reclazz.platform.SapProcessResources", false, loader);
                    Class<?> kinds = Class.forName("com.onurkat.reclazz.watcher.ChangeKind", false, loader);
                    row.put("samePhysicalFile", java.nio.file.Files.isSameFile(observed, advertised));
                    row.put("observedModule", platform.getClass().getMethod("resolveModuleName", java.nio.file.Path.class).invoke(platform, observed));
                    row.put("advertisedModule", platform.getClass().getMethod("resolveModuleName", java.nio.file.Path.class).invoke(platform, advertised));
                    row.put("registeredAtObserved", registrations.getMethod("isRegistered", java.nio.file.Path.class).invoke(null, observed));
                    row.put("registeredAtAdvertised", registrations.getMethod("isRegistered", java.nio.file.Path.class).invoke(null, advertised));
                    row.put("observedKind", kinds.getMethod("of", java.nio.file.Path.class).invoke(null, observed).toString());
                    row.put("advertisedKind", kinds.getMethod("of", java.nio.file.Path.class).invoke(null, advertised).toString());
                }
                return row;
            }));
        }
        if (rows.isEmpty()) throw new IllegalStateException("No observed synthetic resource owners");
        return rows;
    }
    @GetMapping("/impex-refusal")
    public Map<String, Object> impexRefusal() {
        List<TitleModel> titles = searchService.<TitleModel>search("SELECT {pk} FROM {Title} WHERE {code}=?code",
                Map.of("code", "reclazzTestTitle")).getResult();
        int bait = searchService.search("SELECT {pk} FROM {Title} WHERE {code}=?code",
                Map.of("code", "reclazzBatchRemoveBait")).getResult().size();
        return Map.of("titleCount", titles.size(), "title", titles.isEmpty() ? "absent" : titles.get(0).getName(java.util.Locale.ENGLISH),
                "baitCount", bait);
    }
    @GetMapping("/xml")
    public List<Map<String, Object>> xml() {
        var rows = new ArrayList<Map<String, Object>>();
        var owners = FixtureOwners.of("reclazztest-batch-spring.xml");
        for (int i = 0; i < owners.size(); i++) {
            var owner = owners.get(i); int ordinal = i + 1;
            rows.add(FixtureOwners.execute(owner, () -> {
                var factory = owner.getBeanFactory();
                XmlProbe property = owner.getBean("reclazzBatchProperty", XmlProbe.class);
                XmlProbe constructed = owner.getBean("reclazzBatchConstructed", XmlProbe.class);
                XmlHolder holder = owner.getBean("reclazzBatchHolder", XmlHolder.class);
                var row = new LinkedHashMap<String, Object>();
                row.put("owner", ordinal); row.put("value", property.getValue());
                row.put("reference", property.getReference().getConstructed());
                row.put("constructed", constructed.getConstructed());
                row.put("constructedIdentity", System.identityHashCode(constructed));
                row.put("holderIdentity", System.identityHashCode(holder.getProbe()));
                row.put("stableIdentity", System.identityHashCode(owner.getBean("reclazzBatchStable")));
                row.put("addedDefinition", factory.containsBeanDefinition("reclazzBatchAddedBean"));
                Object added = factory.getSingleton("reclazzBatchAddedBean");
                row.put("added", added instanceof XmlProbe ? ((XmlProbe) added).getValue() : "absent");
                return row;
            }));
        }
        if (rows.isEmpty()) throw new IllegalStateException("No observed synthetic XML owners");
        return rows;
    }
    @PostMapping("/xml-cleanup")
    public List<Map<String, Object>> cleanupXml() {
        for (var owner : FixtureOwners.of("reclazztest-batch-spring.xml")) FixtureOwners.execute(owner, () -> {
            var factory = (DefaultListableBeanFactory) owner.getBeanFactory();
            String name = "reclazzBatchAddedBean";
            Object value = factory.getSingleton(name);
            if (value != null && (value.getClass() != XmlProbe.class || !"added-v2".equals(((XmlProbe) value).getValue())))
                throw new IllegalStateException("Reserved synthetic bean ownership mismatch");
            if (factory.containsBeanDefinition(name) && !XmlProbe.class.getName().equals(factory.getBeanDefinition(name).getBeanClassName()))
                throw new IllegalStateException("Reserved definition ownership mismatch");
            factory.destroySingleton(name);
            if (factory.containsBeanDefinition(name)) factory.removeBeanDefinition(name);
            return null;
        });
        return xml();
    }
    @GetMapping("/config")
    public Map<String, String> config() {
        return Map.of("value", Config.getParameter("reclazztest.batch.value"),
                "stable", Config.getParameter("reclazztest.batch.stable"));
    }
    @GetMapping("/localization")
    public String localization() { return Localization.getLocalizedString("reclazz.fixture.text"); }
}
