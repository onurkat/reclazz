package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.interceptors.AddedMappingInterceptor;
import com.onurkat.reclazztest.interceptors.AddedMappingProbe;
import de.hybris.platform.core.Registry;
import de.hybris.platform.servicelayer.interceptor.impl.DefaultInterceptorRegistry;
import de.hybris.platform.servicelayer.interceptor.impl.InterceptorMapping;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Synthetic test-extension surface. Registration is never performed by this probe. */
@RestController
@RequestMapping("/test/mapping-probe")
public class MappingVerificationController {
    private static final String TARGET = "reclazzAddedMappingInterceptor";
    private static final String EDITED = "reclazzEditedMappingInterceptor";
    private static final String MAPPING = "reclazzAddedInterceptorMapping";
    @Autowired private SapVerificationController saveProbe;
    @Autowired private FlexibleSearchService flexibleSearchService;

    private DefaultListableBeanFactory factory() {
        ApplicationContext context = Registry.getApplicationContext();
        while (context != null) {
            if (context instanceof ConfigurableApplicationContext) {
                DefaultListableBeanFactory beans = (DefaultListableBeanFactory)
                        ((ConfigurableApplicationContext) context).getBeanFactory();
                if (beans.containsBeanDefinition(TARGET) || beans.containsBeanDefinition(MAPPING)) return beans;
            }
            context = context.getParent();
        }
        return (DefaultListableBeanFactory) ((ConfigurableApplicationContext) Registry.getApplicationContext()).getBeanFactory();
    }
    private List<ConfigurableApplicationContext> xmlOwners() {
        try {
            ClassLoader loader = ClassLoader.getSystemClassLoader();
            Class<?> holder = Class.forName("com.onurkat.reclazz.platform.ApplicationContextHolder", false, loader);
            Class<?> sources = Class.forName("com.onurkat.reclazz.platform.SpringXmlResources", false, loader);
            List<?> contexts = (List<?>) holder.getMethod("getAllContexts").invoke(null);
            Path source = ((java.util.Set<Path>) sources.getMethod("loadedFiles").invoke(null)).stream()
                    .filter(path -> path.getFileName().toString().equals("reclazztest-mapping-probe-spring.xml"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Synthetic XML source not observed"));
            List<?> owners = (List<?>) sources.getMethod("owners", Path.class, List.class).invoke(null, source, contexts);
            List<ConfigurableApplicationContext> result = new ArrayList<>();
            for (Object owner : owners) result.add((ConfigurableApplicationContext) owner);
            return result;
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Synthetic XML ownership unavailable", failure); }
    }
    private DefaultInterceptorRegistry registry() {
        return Registry.getApplicationContext().getBean("interceptorRegistry", DefaultInterceptorRegistry.class);
    }
    private void inOwner(ConfigurableApplicationContext owner, Callable<Object> action) {
        try {
            // Cleanup uses the agent's existing exact-owner tenant/session bridge; it never registers anything.
            Class<?> scope = Class.forName("com.onurkat.reclazz.hybris.SapOwnerScope", false, ClassLoader.getSystemClassLoader());
            var execute = scope.getDeclaredMethod("execute", Object.class, Callable.class);
            execute.setAccessible(true);
            // An HTTP request is bound to its web tenant; another owner's session needs a plain thread.
            FutureTask<Object> task = new FutureTask<>(() -> execute.invoke(null, owner, action));
            Thread worker = new Thread(task, "reclazz-test-mapping-cleanup");
            worker.setDaemon(true);
            worker.start();
            task.get(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Synthetic owner cleanup failed", failure);
        }
    }
    private int ownedRecords(String nonce) {
        String code = "reclazz-probe-" + nonce;
        int count = 0;
        for (String query : List.of(
                "SELECT COUNT({pk}) FROM {Catalog} WHERE {id}=?code",
                "SELECT COUNT({pk}) FROM {Product} WHERE {code}=?code",
                "SELECT COUNT({v.pk}) FROM {CatalogVersion AS v JOIN Catalog AS c ON {v.catalog}={c.pk}} WHERE {c.id}=?code")) {
            FlexibleSearchQuery search = new FlexibleSearchQuery(query);
            search.addQueryParameter("code", code);
            search.setResultClassList(List.of(Long.class));
            // Only the nonce-scoped cleanup proof: don't let session catalog filters hide retained rows.
            search.setDisableSearchRestrictions(true);
            Number value = (Number) flexibleSearchService.search(search).getResult().get(0);
            count += value.intValue();
        }
        return count;
    }
    private Object mappingTarget(DefaultListableBeanFactory beans) {
        Object value = beans.getSingleton(MAPPING);
        return value instanceof InterceptorMapping ? ((InterceptorMapping) value).getInterceptor() : null;
    }
    private String state() {
        DefaultListableBeanFactory beans = factory();
        Object added = mappingTarget(beans);
        var interceptors = registry().getValidateInterceptors("Product");
        long owned = interceptors.stream().filter(value -> value instanceof AddedMappingInterceptor).count();
        int definitions = 0;
        for (ConfigurableApplicationContext owner : xmlOwners()) {
            if (owner.getBeanFactory().containsBeanDefinition(MAPPING)) definitions++;
        }
        return "bean=" + (beans.getSingleton(TARGET) != null) + ";definition=" + beans.containsBeanDefinition(MAPPING)
                + ";registered=" + (added != null && interceptors.contains(added))
                + ";ownedRegistered=" + owned + ";total=" + interceptors.size()
                + ";xmlOwners=" + xmlOwners().size() + ";ownedDefinitions=" + definitions
                + ";editedBean=" + (beans.getSingleton(EDITED) != null)
                + ";oldRegistered=" + interceptors.stream().filter(value -> value == beans.getSingleton(TARGET)).count()
                + ";newRegistered=" + interceptors.stream().filter(value -> value == beans.getSingleton(EDITED)).count()
                + ";mappingIdentity=" + System.identityHashCode(beans.getSingleton(MAPPING))
                + ";mappingType=" + (beans.getSingleton(MAPPING) instanceof InterceptorMapping
                        ? ((InterceptorMapping) beans.getSingleton(MAPPING)).getTypeCode() : "absent");
    }
    @GetMapping("/registry-proof")
    public String registryProof() throws Exception {
        var field = SapVerificationController.class.getDeclaredField("modelService");
        field.setAccessible(true);
        Object model = field.get(saveProbe);
        if (model instanceof org.springframework.aop.framework.Advised)
            model = ((org.springframework.aop.framework.Advised) model).getTargetSource().getTarget();
        Object actual = model.getClass().getMethod("getInterceptorRegistry").invoke(model);
        List<ConfigurableApplicationContext> owners = xmlOwners();
        int[] matches = {0};
        for (ConfigurableApplicationContext owner : owners) inOwner(owner, () -> {
            var beans = owner.getBeanFactory();
            Object current = owner.getBean("interceptorRegistry");
            int count = 0;
            boolean same = true;
            for (String name : beans.getBeanNamesForType(de.hybris.platform.servicelayer.model.ModelService.class, false, false)) {
                Object service = beans.getSingleton(((DefaultListableBeanFactory) beans).canonicalName(name));
                if (service == null) continue;
                if (service instanceof org.springframework.aop.framework.Advised)
                    service = ((org.springframework.aop.framework.Advised) service).getTargetSource().getTarget();
                same &= service != null && service.getClass().getMethod("getInterceptorRegistry").invoke(service) == current;
                count++;
            }
            if (count > 0 && same) matches[0]++;
            return null;
        });
        return "sameRegistry=" + (actual == registry())
                + ";modelTotal=" + ((DefaultInterceptorRegistry) actual).getValidateInterceptors("Product").size()
                + ";contextTotal=" + registry().getValidateInterceptors("Product").size()
                + ";xmlOwners=" + owners.size() + ";ownerMatches=" + matches[0];
    }
    @GetMapping("/state")
    public String readState() { return state(); }

    /** Anonymous owner snapshots; reads the registry each existing ModelService actually retains. */
    @GetMapping("/owner-state")
    public String ownerState() {
        List<String> rows = new ArrayList<>();
        List<ConfigurableApplicationContext> owners = xmlOwners();
        for (int index = 0; index < owners.size(); index++) {
            ConfigurableApplicationContext owner = owners.get(index);
            int ordinal = index + 1;
            inOwner(owner, () -> {
                DefaultListableBeanFactory beans = (DefaultListableBeanFactory) owner.getBeanFactory();
                Object mapping = beans.getSingleton(MAPPING);
                InterceptorMapping nativeMapping = mapping instanceof InterceptorMapping ? (InterceptorMapping) mapping : null;
                Object target = nativeMapping == null ? null : nativeMapping.getInterceptor();
                String type = nativeMapping == null ? "absent" : nativeMapping.getTypeCode();
                Object contextRegistry = owner.getBean("interceptorRegistry");
                java.util.Set<Object> registries = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                boolean same = true;
                int services = 0;
                for (String name : beans.getBeanNamesForType(de.hybris.platform.servicelayer.model.ModelService.class, false, false)) {
                    Object service = beans.getSingleton(beans.canonicalName(name));
                    if (service == null) continue;
                    if (service instanceof org.springframework.aop.framework.Advised)
                        service = ((org.springframework.aop.framework.Advised) service).getTargetSource().getTarget();
                    Object registry = service.getClass().getMethod("getInterceptorRegistry").invoke(service);
                    same &= registry == contextRegistry;
                    registries.add(registry);
                    services++;
                }
                int mappingCount = -1, typeCount = -1, targetCount = -1;
                if (nativeMapping != null && registries.size() == 1) {
                    Object registry = registries.iterator().next();
                    var resolved = ((DefaultInterceptorRegistry) registry).getValidateInterceptors(type);
                    targetCount = (int) resolved.stream().filter(value -> value == target).count();
                    var field = DefaultInterceptorRegistry.class.getDeclaredField("typeToInterceptorsMap");
                    field.setAccessible(true);
                    mappingCount = 0; typeCount = 0;
                    for (var entry : ((java.util.Map<?, ?>) field.get(registry)).entrySet()) {
                        var mappings = entry.getValue().getClass().getDeclaredField("mappings");
                        mappings.setAccessible(true);
                        for (Object value : (java.util.Collection<?>) mappings.get(entry.getValue())) {
                            if (value != mapping) continue;
                            mappingCount++;
                            if (type.equalsIgnoreCase((String) entry.getKey())) typeCount++;
                        }
                    }
                }
                String targetName = target == null ? "absent"
                        : target == beans.getSingleton(TARGET) ? TARGET
                        : target == beans.getSingleton(EDITED) ? EDITED : "unexpected";
                rows.add("owner=" + ordinal + ";mappingIdentity=" + System.identityHashCode(mapping)
                        + ";mappingType=" + type + ";targetBean=" + targetName
                        + ";targetClass=" + (target instanceof AddedMappingInterceptor ? AddedMappingInterceptor.class.getName() : "absent")
                        + ";registrySame=" + same + ";modelServices=" + services
                        + ";registryCount=" + registries.size() + ";mappingCount=" + mappingCount
                        + ";typeMappingCount=" + typeCount + ";targetCount=" + targetCount);
                return null;
            });
        }
        return String.join("\n", rows);
    }

    @PostMapping("/save")
    public String save(@RequestParam("nonce") String nonce) {
        if (!nonce.matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid synthetic nonce");
        AddedMappingProbe.begin("reclazz-probe-" + nonce);
        try {
            String original = saveProbe.validateSave(nonce); // owns its transaction and rolls it back
            return "nonce=" + nonce + ";base=" + original + ";addedCalls=" + AddedMappingProbe.calls()
                    + ";calledByCurrent=" + AddedMappingProbe.calledBy(mappingTarget(factory()))
                    + ";oldCalls=" + AddedMappingProbe.callsBy(factory().getSingleton(TARGET))
                    + ";newCalls=" + AddedMappingProbe.callsBy(factory().getSingleton(EDITED))
                    + ";ownedRecords=" + ownedRecords(nonce) + ";" + state();
        } finally { AddedMappingProbe.clear(); }
    }

    @PostMapping("/cleanup")
    public String cleanup() {
        for (ConfigurableApplicationContext owner : xmlOwners()) {
            inOwner(owner, () -> {
            DefaultListableBeanFactory beans = (DefaultListableBeanFactory) owner.getBeanFactory();
            Object added = beans.getSingleton(TARGET);
            Object edited = beans.getSingleton(EDITED);
            Object mapping = beans.getSingleton(MAPPING);
            if (edited != null && edited.getClass() != AddedMappingInterceptor.class)
                throw new IllegalStateException("Synthetic edited bean owner mismatch");
            if (added != null && added.getClass() != AddedMappingInterceptor.class)
                throw new IllegalStateException("Synthetic bean owner mismatch");
            if (mapping != null) {
                if (!(mapping instanceof InterceptorMapping)
                        || (((InterceptorMapping) mapping).getInterceptor() != added && ((InterceptorMapping) mapping).getInterceptor() != edited)
                        || !List.of("Product", "GenericItem").contains(((InterceptorMapping) mapping).getTypeCode()))
                    throw new IllegalStateException("Synthetic mapping owner mismatch");
                DefaultInterceptorRegistry nativeRegistry = owner.getBean("interceptorRegistry", DefaultInterceptorRegistry.class);
                if (nativeRegistry.getValidateInterceptors(((InterceptorMapping) mapping).getTypeCode()).contains(((InterceptorMapping) mapping).getInterceptor()))
                    nativeRegistry.unregisterInterceptor((InterceptorMapping) mapping);
            }
            // Only these two reserved test definitions are owned in each observed XML context.
            for (String name : List.of(MAPPING, TARGET, EDITED)) {
                beans.destroySingleton(name);
                if (beans.containsBeanDefinition(name)) beans.removeBeanDefinition(name);
            }
            return null;
            });
        }
        return state();
    }
}
