package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.ExecutionProbe;
import de.hybris.platform.core.PK;
import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.cronjob.model.CronJobModel;
import de.hybris.platform.cronjob.model.TriggerModel;
import de.hybris.platform.task.TaskService;
import de.hybris.platform.task.model.TriggerTaskModel;
import de.hybris.platform.servicelayer.cronjob.CronJobService;
import de.hybris.platform.servicelayer.internal.model.ServicelayerJobModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.user.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Persists owned native tasks; only SAP's worker invokes the existing job. */
@RestController
@RequestMapping("/test/batch/trigger")
public class TriggerProbeController {
    @Autowired private ModelService modelService;
    @Autowired private FlexibleSearchService searchService;
    @Autowired private CronJobService cronJobService;
    @Autowired private TaskService taskService;
    @Autowired private UserService userService;
    private final Map<String, Scope> scopes = new HashMap<>();
    private static final class Scope {
        final String code;
        final List<PK> saved = new ArrayList<>();
        final List<TriggerModel> triggers = new ArrayList<>();
        ServicelayerJobModel job;
        CronJobModel cron;
        Map<String, Object> admission = Map.of();
        int phase;
        boolean woke;
        long executionDate;
        Scope(String code) { this.code = code; }
    }
    private String code(String nonce) {
        if (!nonce.matches("[a-z0-9-]{1,60}")) throw new IllegalArgumentException("Invalid synthetic nonce");
        return "reclazz-trigger-" + nonce;
    }
    private Scope scope(String nonce) {
        code(nonce); Scope s = scopes.get(nonce);
        if (s == null) throw new IllegalStateException("No owned scope");
        return s;
    }
    private void save(Scope s, ItemModel item) {
        try { modelService.save(item); }
        finally { if (item.getPk() != null && !s.saved.contains(item.getPk())) s.saved.add(item.getPk()); }
    }
    private <T> List<T> rows(String query, String param, Object value) {
        var q = new FlexibleSearchQuery(query); q.addQueryParameter(param, value); q.setDisableSearchRestrictions(true);
        return searchService.<T>search(q).getResult();
    }
    private List<TriggerTaskModel> tasks(TriggerModel trigger) {
        return rows("SELECT {pk} FROM {TriggerTask} WHERE {trigger}=?trigger", "trigger", trigger.getPk());
    }
    @PostMapping("/begin")
    public synchronized Map<String, Object> begin(@RequestParam("nonce") String nonce) {
        String code = code(nonce);
        if (!taskService.getEngine().isRunning()) throw new IllegalStateException("Native task engine is stopped");
        if (scopes.containsKey(nonce) || ExecutionProbe.calls(code) != 0
                || !rows("SELECT {pk} FROM {CronJob} WHERE {code}=?code", "code", code).isEmpty()
                || !rows("SELECT {pk} FROM {Job} WHERE {code}=?code", "code", code).isEmpty())
            throw new IllegalStateException("Nonce already used");
        Scope s = new Scope(code); scopes.put(nonce, s); // Keep partial setup reachable by cleanup.
        s.job = modelService.create(ServicelayerJobModel.class);
        s.job.setCode(code); s.job.setSpringId("reclazzNativeProbeJob"); save(s, s.job);
        s.cron = modelService.create(CronJobModel.class);
        s.cron.setCode(code); s.cron.setJob(s.job); s.cron.setSessionUser(userService.getAdminUser());
        s.cron.setLogToDatabase(false); s.cron.setLogToFile(false); save(s, s.cron);
        return Map.of("nonce", nonce, "code", code, "cronPk", s.cron.getPk().toString(), "engineRunning", true);
    }
    @PostMapping("/arm")
    public synchronized Map<String, Object> arm(@RequestParam("nonce") String nonce, @RequestParam("phase") int phase) {
        Scope s = scope(nonce);
        modelService.refresh(s.cron);
        if (phase != s.phase + 1 || phase > 3 || ExecutionProbe.calls(s.code) != phase - 1 || cronJobService.isRunning(s.cron))
            throw new IllegalStateException("Previous owned firing incomplete");
        long due = (System.currentTimeMillis() / 1000 + 3) * 1000;
        Calendar date = Calendar.getInstance(); date.setTimeInMillis(due);
        String expression = String.format(Locale.ROOT, "%d %d %d %d %d ? %d", date.get(Calendar.SECOND),
                date.get(Calendar.MINUTE), date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.DAY_OF_MONTH),
                date.get(Calendar.MONTH) + 1, date.get(Calendar.YEAR));
        TriggerModel trigger = modelService.create(TriggerModel.class); s.triggers.add(trigger);
        trigger.setCronJob(s.cron); trigger.setCronExpression(expression); trigger.setActive(true);
        trigger.setActivationTime(new Date(due)); save(s, trigger); modelService.refresh(trigger);
        var tasks = tasks(trigger);
        for (var task : tasks) if (!s.saved.contains(task.getPk())) s.saved.add(task.getPk());
        if (tasks.size() != 1) throw new IllegalStateException("Expected one native TriggerTask: " + tasks.size());
        TriggerTaskModel task = tasks.get(0);
        long executionDate = task.getExecutionDate().getTime();
        long admittedAt = System.currentTimeMillis();
        if (trigger.getActivationTime().getTime() != due || executionDate < due
                || !task.getTrigger().getPk().equals(trigger.getPk()) || !"triggerTaskRunner".equals(task.getRunnerBean())
                || admittedAt >= executionDate)
            throw new IllegalStateException("Native task admission mismatch: expected=" + due
                    + " activation=" + trigger.getActivationTime().getTime() + " execution=" + task.getExecutionDate().getTime()
                    + " trigger=" + trigger.getPk() + " taskTrigger=" + task.getTrigger().getPk()
                    + " runner=" + task.getRunnerBean() + " now=" + System.currentTimeMillis());
        s.phase = phase; s.executionDate = executionDate; s.woke = false;
        s.admission = Map.ofEntries(Map.entry("nonce", nonce), Map.entry("phase", phase),
                Map.entry("cronPk", s.cron.getPk().toString()), Map.entry("triggerPk", trigger.getPk().toString()),
                Map.entry("taskPk", task.getPk().toString()), Map.entry("taskTriggerPk", task.getTrigger().getPk().toString()),
                Map.entry("runner", task.getRunnerBean()), Map.entry("activation", due),
                Map.entry("executionDate", executionDate), Map.entry("admittedAt", admittedAt),
                Map.entry("offsetMs", executionDate - due));
        return s.admission;
    }
    @GetMapping("/read")
    public synchronized Map<String, Object> read(@RequestParam("nonce") String nonce) {
        Scope s = scope(nonce); modelService.refresh(s.cron);
        var result = new LinkedHashMap<>(s.admission);
        result.put("version", ExecutionProbe.version(s.code)); result.put("calls", ExecutionProbe.calls(s.code));
        result.put("firedAt", ExecutionProbe.time(s.code)); result.put("thread", ExecutionProbe.thread(s.code));
        result.put("result", String.valueOf(s.cron.getResult())); result.put("status", String.valueOf(s.cron.getStatus()));
        return result;
    }
    @PostMapping("/wake")
    public synchronized Map<String, Object> wake(@RequestParam("nonce") String nonce) {
        Scope s = scope(nonce);
        if (s.phase == 0 || System.currentTimeMillis() < s.executionDate) throw new IllegalStateException("Task not due");
        if (!s.woke && ExecutionProbe.calls(s.code) < s.phase) {
            s.woke = true; taskService.getEngine().triggerRepoll(null, null);
        }
        return Map.of("nonce", nonce, "phase", s.phase, "woke", s.woke);
    }
    @PostMapping("/cleanup")
    public synchronized Map<String, Object> cleanup(@RequestParam("nonce") String nonce) throws InterruptedException {
        String code = code(nonce); Scope s = scopes.get(nonce);
        if (s == null) return Map.of("nonce", nonce, "ownedRemaining", 0L, "calls", ExecutionProbe.calls(code), "firedAt", ExecutionProbe.time(code));
        for (var trigger : s.triggers) if (!modelService.isNew(trigger) && !modelService.isRemoved(trigger)) {
            trigger.setActive(false); save(s, trigger);
        }
        if (s.cron != null && !modelService.isNew(s.cron) && !modelService.isRemoved(s.cron)) {
            long deadline = System.nanoTime() + 30_000_000_000L;
            modelService.refresh(s.cron);
            while (cronJobService.isRunning(s.cron)) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("Owned CronJob still running");
                Thread.sleep(50); modelService.refresh(s.cron);
            }
        }
        for (var trigger : s.triggers) if (trigger.getPk() != null) {
            for (var task : tasks(trigger)) { if (!s.saved.contains(task.getPk())) s.saved.add(task.getPk()); modelService.remove(task); }
            if (!modelService.isRemoved(trigger)) modelService.remove(trigger);
        }
        if (s.cron != null && !modelService.isNew(s.cron) && !modelService.isRemoved(s.cron)) {
            modelService.refresh(s.cron);
            var children = new ArrayList<ItemModel>(); children.addAll(s.cron.getLogs());
            children.addAll(s.cron.getLogFiles()); children.addAll(s.cron.getCronJobHistoryEntries());
            for (var child : children) if (!s.saved.contains(child.getPk())) s.saved.add(child.getPk());
            modelService.removeAll(children); modelService.remove(s.cron);
        }
        if (s.job != null && !modelService.isNew(s.job) && !modelService.isRemoved(s.job)) modelService.remove(s.job);
        long remaining = s.saved.isEmpty() ? 0 : rows("SELECT {pk} FROM {Item} WHERE {pk} IN (?owned)", "owned", s.saved).size();
        for (var trigger : s.triggers) if (trigger.getPk() != null) remaining += tasks(trigger).size();
        if (remaining != 0) throw new IllegalStateException("Owned rows remain: " + remaining);
        ExecutionProbe.clear(code); scopes.remove(nonce);
        return Map.of("nonce", nonce, "ownedRemaining", remaining, "calls", ExecutionProbe.calls(code), "firedAt", ExecutionProbe.time(code));
    }
}
