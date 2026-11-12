package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.ProbeScheduledMetadataState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Inspects the owned prepared timer; it never invokes or registers a tick. */
@RestController
@RequestMapping("/test/batch/scheduled-metadata")
public class ScheduledMetadataProbeController {
    private static final String BEAN = "reclazzScheduledMetadataJob";
    private static final String TYPE = "com.onurkat.reclazztest.services.ProbeScheduledMetadataJob";
    @Autowired private ApplicationContext context;
    private ApplicationContext owner() {
        ApplicationContext found = null;
        for (ApplicationContext c = context; c != null; c = c.getParent()) if (c.containsLocalBean(BEAN)) {
            if (found != null) throw new IllegalStateException("Multiple owned scheduling contexts");
            found = c;
        }
        if (found == null) throw new IllegalStateException("No prepared scheduling owner");
        return found;
    }
    private static Runnable originalRunnable(Runnable task) {
        // Installed Spring 6.2 tracks outcomes in this verified delegate wrapper.
        if (!task.getClass().getName().equals("org.springframework.scheduling.config.Task$OutcomeTrackingRunnable")) return task;
        try {
            var field = task.getClass().getDeclaredField("runnable"); field.setAccessible(true);
            return (Runnable) field.get(task);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot inspect native task delegate", failure); }
    }
    private Map<String, Object> registration(ApplicationContext owner, Object current) {
        var tasks = new ArrayList<Map<String, Object>>();
        for (var processor : owner.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).values())
            for (var scheduled : processor.getScheduledTasks()) {
                var task = scheduled.getTask();
                if (originalRunnable(task.getRunnable()) instanceof ScheduledMethodRunnable method
                        && TYPE.equals(method.getMethod().getDeclaringClass().getName())
                        && "tick".equals(method.getMethod().getName()))
                    tasks.add(Map.of("method", TYPE + ".tick", "fixedDelay", task instanceof FixedDelayTask,
                            "intervalMs", task instanceof FixedDelayTask delay ? delay.getIntervalDuration().toMillis() : -1L,
                            "sameReceiver", method.getTarget() == current, "runnableClass", task.getRunnable().getClass().getName()));
            }
        return Map.of("ownerContext", owner.getId(), "contextIdentity", System.identityHashCode(owner),
                "ownedTasks", tasks.size(), "tasks", tasks);
    }
    private Map<String, Object> proof(String nonce) {
        var owner = owner(); var current = owner.getBean(BEAN);
        var result = new LinkedHashMap<>(ProbeScheduledMetadataState.snapshot(nonce, current));
        result.putAll(registration(owner, current)); return result;
    }
    @PostMapping("/begin")
    public Map<String, Object> begin(@RequestParam("nonce") String nonce) {
        ProbeScheduledMetadataState.begin(nonce); return proof(nonce);
    }
    @PostMapping("/phase")
    public Map<String, Object> phase(@RequestParam("nonce") String nonce, @RequestParam("phase") int phase) {
        ProbeScheduledMetadataState.phase(nonce, phase); return proof(nonce);
    }
    @GetMapping("/read")
    public Map<String, Object> read(@RequestParam("nonce") String nonce) { return proof(nonce); }
    @PostMapping("/cleanup")
    public Map<String, Object> cleanup(@RequestParam("nonce") String nonce) {
        var result = new LinkedHashMap<>(ProbeScheduledMetadataState.cleanup(nonce));
        var owner = owner(); result.putAll(registration(owner, owner.getBean(BEAN))); return result;
    }
}
