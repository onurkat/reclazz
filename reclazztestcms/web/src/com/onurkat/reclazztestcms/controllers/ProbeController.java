package com.onurkat.reclazztestcms.controllers;

import de.hybris.platform.acceleratorstorefrontcommons.controllers.cms.AbstractCMSComponentController;
import de.hybris.platform.cms2.model.contents.components.CMSParagraphComponentModel;
import de.hybris.platform.core.Registry;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.type.TypeService;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

/** Profile-only unsaved request-component setup; no UID/catalog lookup or save. */
@Controller
@RequestMapping("/test/batch/cms")
public class ProbeController {
    @Autowired private ModelService modelService;
    @Autowired private FlexibleSearchService searchService;
    @Autowired private TypeService typeService;
    @Autowired private ApplicationContext context;
    @Autowired private ServletContext servletContext;

    private String owned(String nonce) {
        if (!nonce.matches("reclazz-cms-[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid owned CMS nonce");
        return nonce;
    }

    private Object platform() throws Exception {
        Class<?> agent = Class.forName("com.onurkat.reclazz.agent.ReclazzAgent", false, ClassLoader.getSystemClassLoader());
        var field = agent.getDeclaredField("platformContext"); field.setAccessible(true); return field.get(null);
    }

    private Path superclassDirectory() throws Exception {
        var source = AbstractCMSComponentController.class.getProtectionDomain().getCodeSource();
        if (source == null || !"file".equals(source.getLocation().getProtocol()))
            throw new IllegalStateException("Native superclass provenance unavailable");
        return Path.of(source.getLocation().toURI()).toRealPath();
    }

    @GetMapping("/gates") @ResponseBody
    public Map<String, Object> gates() throws Exception {
        var result = new LinkedHashMap<String, Object>();
        Class<?> agent = Class.forName("com.onurkat.reclazz.agent.ReclazzAgent", false, ClassLoader.getSystemClassLoader());
        Object config = agent.getMethod("getConfig").invoke(null); Object launched = null;
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-javaagent:") && arg.contains("reclazz-agent.jar")) {
                int at = arg.indexOf('=');
                launched = config.getClass().getMethod("parse", String.class).invoke(null, at < 0 ? "" : arg.substring(at + 1));
            }
        }
        result.put("agentArgsFound", launched != null); boolean same = launched != null;
        for (String option : List.of("autoCompile", "autoSolrReindex", "resetStatics", "autoUpdateRunningSystem", "impexAllowRemove")) {
            String getter = "is" + Character.toUpperCase(option.charAt(0)) + option.substring(1);
            boolean actual = launched != null && (Boolean) config.getClass().getMethod(getter).invoke(launched);
            same &= actual == (Boolean) config.getClass().getMethod(getter).invoke(config); result.put(option, actual);
        }
        result.put("agentOptionsMatchConfig", same);
        Object platform = platform(); Object hybris = platform.getClass().getMethod("getHybrisContext").invoke(platform);
        var extensions = (Map<?, ?>) hybris.getClass().getMethod("getExtensions").invoke(hybris);
        result.put("profileExtensionLoaded", extensions.containsKey("reclazztestcms"));
        result.put("cmsExtensionsLoaded", extensions.containsKey("acceleratorstorefrontcommons") && extensions.containsKey("acceleratorcms") && extensions.containsKey("cms2"));
        result.put("profileWatched", platform.getClass().getMethod("shouldWatch", String.class).invoke(platform, "reclazztestcms"));
        result.put("masterTenant", "master".equals(Registry.getCurrentTenant().getTenantID()));
        result.put("cmsModelAvailable", CMSParagraphComponentModel.class.getName().equals("de.hybris.platform.cms2.model.contents.components.CMSParagraphComponentModel"));
        result.put("cmsTypeAvailable", typeService.getComposedTypeForCode("CMSParagraphComponent") != null);
        result.put("nativeBeansPresent", context.containsBean("cmsComponentService") && context.containsBean("cmsPageContextService"));
        result.put("nativeSuperclass", superclassDirectory().equals(Path.of(servletContext.getRealPath("/WEB-INF/classes")).toRealPath()));
        result.put("inheritedHandler", ReclazzParagraphComponentController.class.getMethod("handleGet", HttpServletRequest.class,
                HttpServletResponse.class, Model.class).getDeclaringClass() == AbstractCMSComponentController.class);
        result.put("jspViewResolver", context.getBeansOfType(InternalResourceViewResolver.class).size() == 1 && servletContext.getServletRegistration("jsp") != null);
        return result;
    }

    @GetMapping("/facts") @ResponseBody
    public Map<String, Object> facts() throws Exception {
        var result = new LinkedHashMap<String, Object>(); result.put("gates", gates());
        result.put("paragraphCount", searchService.search("SELECT {pk} FROM {CMSParagraphComponent}").getResult().size());
        result.put("modelType", "CMSParagraphComponent"); result.put("tenant", Registry.getCurrentTenant().getTenantID());
        Path directory = superclassDirectory(); result.put("superclassDirectory", directory.toString());
        byte[] bytes = Files.readAllBytes(directory.resolve("de/hybris/platform/acceleratorstorefrontcommons/controllers/cms/AbstractCMSComponentController.class"));
        result.put("superclassSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        result.put("componentServiceClass", context.getBean("cmsComponentService").getClass().getName());
        result.put("pageContextServiceClass", context.getBean("cmsPageContextService").getClass().getName());
        return result;
    }

    @GetMapping("/probe")
    public String probe(@RequestParam("nonce") String nonce, HttpServletRequest request) throws Exception {
        owned(nonce);
        for (var entry : gates().entrySet()) {
            boolean expected = !Set.of("autoSolrReindex", "resetStatics", "autoUpdateRunningSystem", "impexAllowRemove").contains(entry.getKey());
            if (!Boolean.valueOf(expected).equals(entry.getValue())) throw new IllegalStateException("CMS runtime gate unavailable: " + entry.getKey());
        }
        CMSParagraphComponentModel component = modelService.create("CMSParagraphComponent");
        modelService.setAttributeValue(component, "uid", nonce);
        component.setContent("reclazz-cms-content", Locale.ENGLISH);
        if (component.getPk() != null) throw new IllegalStateException("Probe component was persisted");
        request.setAttribute("component", component); request.setAttribute("reclazzCmsNonce", nonce);
        return "forward:/v2/test/batch/cms/paragraph";
    }
}
