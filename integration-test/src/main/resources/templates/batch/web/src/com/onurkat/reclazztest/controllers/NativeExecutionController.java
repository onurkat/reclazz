package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.ExecutionProbe;
import com.onurkat.reclazztest.batch.NativeProbeJob;
import de.hybris.platform.core.Registry;
import com.onurkat.reclazztest.batch.NativeProbeEvent;
import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.cronjob.model.CronJobModel;
import de.hybris.platform.processengine.BusinessProcessService;
import de.hybris.platform.processengine.definition.ProcessDefinitionResource;
import de.hybris.platform.processengine.definition.ProcessDefinitionFactory;
import de.hybris.platform.processengine.model.BusinessProcessModel;
import de.hybris.platform.servicelayer.cronjob.CronJobService;
import de.hybris.platform.servicelayer.event.EventService;
import de.hybris.platform.servicelayer.internal.model.ServicelayerJobModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.user.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Actual native service calls, confined to nonce-owned synthetic records. */
@RestController
@RequestMapping("/test/batch/native")
public class NativeExecutionController {
    @Autowired private ModelService modelService;
    @Autowired private FlexibleSearchService searchService;
    @Autowired private BusinessProcessService businessProcessService;
    @Autowired private CronJobService cronJobService;
    @Autowired private EventService eventService;
    @Autowired private UserService userService;

    private String code(String nonce) {
        if (!nonce.matches("[a-z0-9-]{1,60}")) throw new IllegalArgumentException("Invalid synthetic nonce");
        return "reclazz-batch-" + nonce;
    }
    private long count(String type, String property, Object value) {
        var query = new FlexibleSearchQuery("SELECT COUNT({pk}) FROM {" + type + "} WHERE {" + property + "}=?value");
        query.addQueryParameter("value", value); query.setDisableSearchRestrictions(true);
        query.setResultClassList(List.of(Long.class));
        return ((Number) searchService.search(query).getResult().get(0)).longValue();
    }
    private long surviving(List<ItemModel> owned) {
        if (owned.isEmpty()) return 0;
        var query = new FlexibleSearchQuery("SELECT COUNT({pk}) FROM {Item} WHERE {pk} IN (?owned)");
        query.addQueryParameter("owned", owned.stream().map(ItemModel::getPk).toList());
        query.setDisableSearchRestrictions(true); query.setResultClassList(List.of(Long.class));
        return ((Number) searchService.search(query).getResult().get(0)).longValue();
    }
    @PostMapping("/event")
    public Map<String, Object> event(@RequestParam("nonce") String nonce) {
        String code = code(nonce);
        if (ExecutionProbe.calls(code) != 0) throw new IllegalStateException("Nonce already used");
        eventService.publishEvent(new NativeProbeEvent(code));
        return execution(code);
    }
    private Map<String, Object> execution(String code) {
        return Map.of("version", ExecutionProbe.version(code), "calls", ExecutionProbe.calls(code));
    }
    @GetMapping("/event")
    public Map<String, Object> eventState(@RequestParam("nonce") String nonce) { return execution(code(nonce)); }
    @PostMapping("/event-cleanup")
    public Map<String, Object> eventCleanup(@RequestParam("nonce") String nonce) {
        String code = code(nonce); ExecutionProbe.clear(code); return execution(code);
    }
    @GetMapping("/cronjob-version")
    public Map<String, String> cronJobVersion(@RequestParam("nonce") String nonce) {
        code(nonce); // Validate ownership only; this read creates no records or probe entries.
        return Map.of("nonce", nonce, "version", Registry.getApplicationContext()
                .getBean("reclazzNativeProbeJob", NativeProbeJob.class).version());
    }
    @PostMapping("/cronjob")
    public Map<String, Object> cronJob(@RequestParam("nonce") String nonce) {
        String code = code(nonce);
        if (count("CronJob", "code", code) != 0 || count("Job", "code", code) != 0)
            throw new IllegalStateException("Nonce already persisted");
        ServicelayerJobModel job = modelService.create(ServicelayerJobModel.class);
        CronJobModel cron = modelService.create(CronJobModel.class);
        var result = new LinkedHashMap<String, Object>();
        try {
            job.setCode(code); job.setSpringId("reclazzNativeProbeJob"); modelService.save(job);
            cron.setCode(code); cron.setJob(job); cron.setSessionUser(userService.getAdminUser());
            cron.setLogToDatabase(false); cron.setLogToFile(false); modelService.save(cron);
            cronJobService.performCronJob(cron, true);
            modelService.refresh(cron);
            result.putAll(execution(code)); result.put("result", String.valueOf(cron.getResult()));
            result.put("status", String.valueOf(cron.getStatus()));
        } finally {
            var children = new ArrayList<ItemModel>();
            try {
                if (!modelService.isNew(cron)) {
                    modelService.refresh(cron);
                    if (cronJobService.isRunning(cron)) throw new IllegalStateException("Owned job still running; cleanup refused");
                    children.addAll(cron.getCronJobHistoryEntries()); children.addAll(cron.getLogs()); children.addAll(cron.getLogFiles());
                    modelService.removeAll(children); modelService.remove(cron);
                }
                if (!modelService.isNew(job)) modelService.remove(job);
                result.put("ownedRemaining", surviving(children) + count("CronJob", "code", code) + count("Job", "code", code));
            } finally { ExecutionProbe.clear(code); }
        }
        return result;
    }
    @PostMapping("/process-start")
    public Map<String, String> start(@RequestParam("nonce") String nonce, @RequestParam("definition") String definition) {
        if (!definition.matches("reclazz-process-[a-z0-9-]{1,80}")) throw new IllegalArgumentException("Not a synthetic process");
        String code = code(nonce);
        if (count("BusinessProcess", "code", code) != 0) throw new IllegalStateException("Nonce already persisted");
        BusinessProcessModel process = businessProcessService.createProcess(code, definition);
        businessProcessService.startProcess(process);
        // Start commits the RUNNING state before the native wait event can finish
        // this fixture; a direct EndNode start is overwritten by the SDK service.
        businessProcessService.triggerEvent(process, "reclazzBatchContinue");
        return Map.of("code", code, "version", String.valueOf(process.getProcessDefinitionVersion()));
    }
    @GetMapping("/process-owners")
    public List<Map<String, Object>> processOwners(@RequestParam("definition") String definition) {
        if (!definition.matches("reclazz-process-[a-z0-9-]{1,80}")) throw new IllegalArgumentException("Not a synthetic process");
        var rows = new ArrayList<Map<String, Object>>();
        var owners = FixtureOwners.of("reclazztest-process-resources-spring.xml");
        for (int i = 0; i < owners.size(); i++) {
            var owner = owners.get(i); int ordinal = i + 1;
            rows.add(FixtureOwners.execute(owner, () -> {
                ProcessDefinitionFactory factory = owner.getBean(ProcessDefinitionFactory.class);
                var graph = factory.getProcessDefinition(definition);
                return Map.of("owner", ordinal, "start", graph.getStartNode().getId(),
                        "version", graph.getVersion(), "identity", System.identityHashCode(graph));
            }));
        }
        if (rows.isEmpty()) throw new IllegalStateException("No synthetic process owners");
        return rows;
    }
    @GetMapping("/process")
    public Map<String, String> process(@RequestParam("nonce") String nonce) {
        BusinessProcessModel process = businessProcessService.getProcess(code(nonce)); modelService.refresh(process);
        return Map.of("state", String.valueOf(process.getState()), "message", String.valueOf(process.getEndMessage()),
                "version", String.valueOf(process.getProcessDefinitionVersion()));
    }
    @PostMapping("/process-cleanup")
    public Map<String, Object> cleanupProcess(@RequestParam("nonce") String nonce) {
        String code = code(nonce);
        if (count("BusinessProcess", "code", code) == 0) return Map.of("ownedRemaining", 0L);
        BusinessProcessModel process = businessProcessService.getProcess(code); modelService.refresh(process);
        if (!Set.of("SUCCEEDED", "FAILED", "ERROR").contains(String.valueOf(process.getState())))
            throw new IllegalStateException("Owned process not terminal; cleanup refused");
        var children = new ArrayList<ItemModel>();
        children.addAll(process.getCurrentTasks()); children.addAll(process.getTaskLogs()); children.addAll(process.getContextParameters());
        modelService.removeAll(children); modelService.remove(process);
        return Map.of("ownedRemaining", surviving(children) + count("BusinessProcess", "code", code));
    }
    @PostMapping("/process-resource-cleanup")
    public Map<String, Integer> cleanupResource(@RequestParam("bean") String bean) {
        if (!bean.matches("reclazzBatchProcessResource[a-z0-9]{1,64}")) throw new IllegalArgumentException("Not an owned resource bean");
        var owners = FixtureOwners.of("reclazztest-process-resources-spring.xml");
        for (var owner : owners) FixtureOwners.execute(owner, () -> {
            var factory = (DefaultListableBeanFactory) owner.getBeanFactory();
            Object value = factory.getSingleton(bean);
            if (value != null && (!(value instanceof ProcessDefinitionResource)
                    || !((ProcessDefinitionResource) value).getResource().getFilename().startsWith("reclazz-process-")))
                throw new IllegalStateException("Synthetic resource ownership mismatch");
            if (factory.containsBeanDefinition(bean) && !ProcessDefinitionResource.class.getName().equals(factory.getBeanDefinition(bean).getBeanClassName()))
                throw new IllegalStateException("Synthetic resource definition ownership mismatch");
            factory.destroySingleton(bean);
            if (factory.containsBeanDefinition(bean)) factory.removeBeanDefinition(bean);
            return null;
        });
        int remaining = 0;
        for (var owner : owners) if (owner.getBeanFactory().containsBeanDefinition(bean) || owner.getBeanFactory().getSingleton(bean) != null) remaining++;
        return Map.of("ownedResourcesRemaining", remaining, "owners", owners.size());
    }
}
