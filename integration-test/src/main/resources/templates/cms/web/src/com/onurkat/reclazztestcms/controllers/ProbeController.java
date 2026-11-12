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

    @Autowired private de.hybris.platform.catalog.CatalogVersionService catalogVersions;
    @Autowired private ReclazzParagraphComponentController renderer;

    private java.util.List<de.hybris.platform.core.model.ItemModel> uidRows(String nonce) {
        owned(nonce);
        return searchService.<de.hybris.platform.core.model.ItemModel>search(
            "SELECT {pk} FROM {Catalog} WHERE {id}=?id", Map.of("id", nonce)).getResult();
    }

    @PostMapping("/uid/begin") @ResponseBody
    public synchronized Map<String,Object> uidBegin(@RequestParam("nonce") String nonce) {
        if (!uidRows(nonce).isEmpty()) throw new IllegalStateException("Owned catalog already exists");
        de.hybris.platform.catalog.model.CatalogModel catalog = modelService.create("ContentCatalog");
        catalog.setId(nonce); modelService.save(catalog);
        for (String version : List.of("selected", "other")) {
            de.hybris.platform.catalog.model.CatalogVersionModel cv = modelService.create("CatalogVersion");
            cv.setCatalog(catalog); cv.setVersion(version); cv.setActive(false); modelService.save(cv);
            for (String role : List.of("target", "control")) {
                CMSParagraphComponentModel c = modelService.create("CMSParagraphComponent");
                modelService.setAttributeValue(c, "uid", nonce + "-" + role);
                modelService.setAttributeValue(c, "catalogVersion", cv);
                c.setContent(version + "-" + role, Locale.ENGLISH); modelService.save(c);
            }
        }
        return Map.of("nonce",nonce,"catalogs",uidRows(nonce).size());
    }

    @GetMapping("/uid/render")
    public String uidRender(@RequestParam("nonce") String nonce, @RequestParam("version") String version,
            @RequestParam("role") String role, HttpServletRequest request, HttpServletResponse response, Model model) throws Exception {
        owned(nonce);
        if (!List.of("selected","other").contains(version) || !List.of("target","control").contains(role)
                || uidRows(nonce).size()!=1) throw new IllegalArgumentException("Invalid owned CMS selection");
        var previous = new ArrayList<>(catalogVersions.getSessionCatalogVersions());
        try {
            var cv=catalogVersions.getCatalogVersion(nonce,version);
            catalogVersions.setSessionCatalogVersions(List.of(cv));
            if (request.getAttribute("component") != null) throw new IllegalStateException("Injected component forbidden");
            request.setAttribute("componentUid",nonce+"-"+role);
            request.setAttribute("reclazzCmsNonce",nonce);
            String view=renderer.handleGet(request,response,model);
            CMSParagraphComponentModel c=(CMSParagraphComponentModel)model.getAttribute("component");
            if(c==null || c.getPk()==null || !cv.equals(modelService.getAttributeValue(c,"catalogVersion")))
                throw new IllegalStateException("Native UID lookup selected wrong persisted version");
            model.addAttribute("uid",modelService.getAttributeValue(c,"uid"));
            model.addAttribute("pk",c.getPk().toString()); model.addAttribute("version",version);
            return view;
        } finally {
            catalogVersions.setSessionCatalogVersions(previous);
            if(!new HashSet<>(previous).equals(new HashSet<>(catalogVersions.getSessionCatalogVersions())))
                throw new IllegalStateException("Session catalog restoration failed");
        }
    }

    @PostMapping("/uid/cleanup") @ResponseBody
    public synchronized Map<String,Object> uidCleanup(@RequestParam("nonce") String nonce) {
        owned(nonce);
        var previous=new ArrayList<>(catalogVersions.getSessionCatalogVersions());
        try {
            var catalogs=uidRows(nonce);
            for(var catalog:catalogs) {
                var versions=searchService.<de.hybris.platform.catalog.model.CatalogVersionModel>search(
                    "SELECT {pk} FROM {CatalogVersion} WHERE {catalog}=?catalog",Map.of("catalog",catalog)).getResult();
                for(var cv:versions) {
                    // Native remove interceptors query categories through session.catalogversions.
                    catalogVersions.setSessionCatalogVersions(List.of(cv));
                    var components=searchService.<CMSParagraphComponentModel>search(
                        "SELECT {pk} FROM {CMSParagraphComponent} WHERE {catalogVersion}=?cv",Map.of("cv",cv)).getResult();
                    for(var c:components) {
                        String uid=modelService.getAttributeValue(c,"uid");
                        if(!List.of(nonce+"-target",nonce+"-control").contains(uid)) throw new IllegalStateException("Foreign component in owned catalog");
                    }
                    modelService.removeAll(components); modelService.remove(cv);
                }
                modelService.remove(catalog);
            }
            int remaining=uidRows(nonce).size()+searchService.search(
                "SELECT {pk} FROM {CMSParagraphComponent} WHERE {uid} IN (?target,?control)",
                Map.of("target",nonce+"-target","control",nonce+"-control")).getResult().size();
            return Map.of("nonce",nonce,"ownedRemaining",remaining);
        } finally {
            catalogVersions.setSessionCatalogVersions(previous);
            if(!new HashSet<>(previous).equals(new HashSet<>(catalogVersions.getSessionCatalogVersions())))
                throw new IllegalStateException("Cleanup session catalog restoration failed");
        }
    }

    private de.hybris.platform.core.model.ItemModel pageItem(String type, String uid,
            de.hybris.platform.catalog.model.CatalogVersionModel cv) {
        de.hybris.platform.core.model.ItemModel item=modelService.create(type);
        modelService.setAttributeValue(item,"uid",uid);modelService.setAttributeValue(item,"catalogVersion",cv);
        return item;
    }

    private de.hybris.platform.cms2.servicelayer.services.CMSPageService pages() {
        return context.getBean("cmsPageService",de.hybris.platform.cms2.servicelayer.services.CMSPageService.class);
    }

    @PostMapping("/page-slot/begin") @ResponseBody
    public synchronized Map<String,Object> pageBegin(@RequestParam("nonce") String nonce) throws Exception {
        uidBegin(nonce);
        var previous=new ArrayList<>(catalogVersions.getSessionCatalogVersions());
        try {
            var components=context.getBean("cmsComponentService",de.hybris.platform.cms2.servicelayer.services.CMSComponentService.class);
            for(String version:List.of("selected","other")) {
                var cv=catalogVersions.getCatalogVersion(nonce,version);catalogVersions.setSessionCatalogVersions(List.of(cv));
                var template=pageItem("PageTemplate",nonce+"-template",cv);modelService.save(template);
                var page=pageItem("ContentPage",nonce+"-page",cv);
                modelService.setAttributeValue(page,"masterTemplate",template);
                modelService.setAttributeValue(page,"label","/"+nonce);modelService.save(page);
                for(String role:List.of("target","control")) {
                    CMSParagraphComponentModel component=components.getAbstractCMSComponent(nonce+"-"+role);
                    component.setContent(version+"-page-"+role,Locale.ENGLISH);modelService.save(component);
                    var slot=pageItem("ContentSlot",nonce+"-"+role+"-slot",cv);
                    modelService.setAttributeValue(slot,"cmsComponents",List.of(component));modelService.save(slot);
                    var placement=pageItem("ContentSlotForPage",nonce+"-"+role+"-placement",cv);
                    modelService.setAttributeValue(placement,"page",page);modelService.setAttributeValue(placement,"contentSlot",slot);
                    modelService.setAttributeValue(placement,"position",role);modelService.save(placement);
                }
            }
            return Map.of("nonce",nonce,"catalogs",uidRows(nonce).size());
        } finally {catalogVersions.setSessionCatalogVersions(previous);}
    }

    @GetMapping("/page-slot/render")
    public String pageRender(@RequestParam("nonce") String nonce,@RequestParam("version") String version,
            @RequestParam("position") String position,HttpServletRequest request,HttpServletResponse response,Model model) throws Exception {
        owned(nonce);
        if(!List.of("selected","other").contains(version)||!List.of("target","control").contains(position)||uidRows(nonce).size()!=1)
            throw new IllegalArgumentException("Invalid owned page selection");
        var previous=new ArrayList<>(catalogVersions.getSessionCatalogVersions());
        try {
            var cv=catalogVersions.getCatalogVersion(nonce,version);catalogVersions.setSessionCatalogVersions(List.of(cv));
            var page=pages().getPageForId(nonce+"-page");
            if(page.getPk()==null||!cv.equals(modelService.getAttributeValue(page,"catalogVersion")))throw new IllegalStateException("Wrong native page version");
            var data=pages().getContentSlotForPage(page,position);var slot=data.getContentSlot();
            if(!position.equals(data.getPosition())||!cv.equals(modelService.getAttributeValue(slot,"catalogVersion"))||slot.getPk()==null)
                throw new IllegalStateException("Wrong native page slot");
            var members=data.getCMSComponents();
            if(members.size()!=1)throw new IllegalStateException("Owned slot membership changed");
            var selected=members.iterator().next();String uid=modelService.getAttributeValue(selected,"uid");
            if(!uid.equals(nonce+"-"+position)||selected.getPk()==null)throw new IllegalStateException("Wrong native slot component");
            if(request.getAttribute("component")!=null)throw new IllegalStateException("Injected page component forbidden");
            request.setAttribute("componentUid",uid);request.setAttribute("reclazzCmsNonce",nonce);
            String view=renderer.handleGet(request,response,model);
            var rendered=(CMSParagraphComponentModel)model.getAttribute("component");
            if(!selected.getPk().equals(rendered.getPk())||!cv.equals(modelService.getAttributeValue(rendered,"catalogVersion")))
                throw new IllegalStateException("UID renderer bypassed selected slot member");
            model.addAttribute("uid",uid);model.addAttribute("pk",rendered.getPk().toString());model.addAttribute("version",version);
            model.addAttribute("pageUid",modelService.getAttributeValue(page,"uid"));model.addAttribute("pagePk",page.getPk().toString());
            model.addAttribute("slotUid",modelService.getAttributeValue(slot,"uid"));model.addAttribute("slotPk",slot.getPk().toString());
            model.addAttribute("position",data.getPosition());return view;
        } finally {
            catalogVersions.setSessionCatalogVersions(previous);
            if(!new HashSet<>(previous).equals(new HashSet<>(catalogVersions.getSessionCatalogVersions())))throw new IllegalStateException("Page session restore failed");
        }
    }

    @PostMapping("/page-slot/cleanup") @ResponseBody
    public synchronized Map<String,Object> pageCleanup(@RequestParam("nonce") String nonce) {
        owned(nonce);var previous=new ArrayList<>(catalogVersions.getSessionCatalogVersions());
        var types=List.of("ContentSlotForPage","ContentPage","ContentSlot","PageTemplate");
        var ownedIds=List.of(nonce+"-page",nonce+"-template",nonce+"-target-slot",nonce+"-control-slot",nonce+"-target-placement",nonce+"-control-placement");
        try {
            for(var catalog:uidRows(nonce)) {
                var versions=searchService.<de.hybris.platform.catalog.model.CatalogVersionModel>search(
                    "SELECT {pk} FROM {CatalogVersion} WHERE {catalog}=?catalog",Map.of("catalog",catalog)).getResult();
                for(var cv:versions) {
                    catalogVersions.setSessionCatalogVersions(List.of(cv));
                    for(String type:types) {
                        var rows=searchService.<de.hybris.platform.core.model.ItemModel>search(
                            "SELECT {pk} FROM {"+type+"} WHERE {catalogVersion}=?cv",Map.of("cv",cv)).getResult();
                        for(var row:rows)if(!ownedIds.contains(modelService.<String>getAttributeValue(row,"uid")))throw new IllegalStateException("Foreign page row in owned catalog");
                        modelService.removeAll(rows);
                    }
                }
            }
            int remaining=0;
            for(String type:types)remaining+=searchService.search("SELECT {pk} FROM {"+type+"} WHERE {uid} IN (?ids)",Map.of("ids",ownedIds)).getResult().size();
            catalogVersions.setSessionCatalogVersions(previous);
            var cleanup=uidCleanup(nonce);
            return Map.of("nonce",nonce,"ownedPageRows",remaining,"ownedRemaining",remaining+((Number)cleanup.get("ownedRemaining")).intValue());
        } finally {
            catalogVersions.setSessionCatalogVersions(previous);
            if(!new HashSet<>(previous).equals(new HashSet<>(catalogVersions.getSessionCatalogVersions())))throw new IllegalStateException("Page cleanup session restore failed");
        }
    }

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

    @GetMapping("/view-facts") @ResponseBody
    public Map<String,Object> viewFacts() throws Exception {
        var jsp=servletContext.getServletRegistration("jsp");
        if(jsp==null)throw new IllegalStateException("Native JSP servlet missing");
        return Map.of("viewPath",Path.of(servletContext.getRealPath("/WEB-INF/views/reclazz-page-slot.jsp")).toRealPath().toString(),
                "jspClass",jsp.getClassName(),"jspInit",jsp.getInitParameters(),
                "jvmStart",ManagementFactory.getRuntimeMXBean().getStartTime());
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
