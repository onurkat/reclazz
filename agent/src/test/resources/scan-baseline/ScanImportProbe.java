/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.example;

import com.onurkat.reclazz.agent.ReclazzAgent;
import com.onurkat.reclazz.agent.ReloadQueue;
import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.watcher.FileWatcher;
import de.hybris.platform.servicelayer.impex.ImpExResource;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual agent/watcher/importer, with a counting import API instead of a tenant database. */
public class ScanImportProbe {
    static final AtomicInteger imports = new AtomicInteger();
    static final Context context = new Context();
    public static class Context {
        final ImportService service = new ImportService();
        public boolean containsBean(String name) { return name.equals("importService"); }
        public Object getBean(String name) { return service; }
    }
    public static class ImportService {
        public Result importData(ImpExResource resource) { imports.incrementAndGet(); return new Result(); }
    }
    public static class Result { public boolean isError() { return false; } }
    static Object field(Class<?> owner, Object target, String name) throws Exception {
        var field=owner.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);
        ApplicationContextHolder.register(context);
        System.out.println("PROBE_READY");
        for(int stage=0;stage<3;stage++) {
            while(!Files.exists(root.resolve("probe"+stage))) Thread.sleep(10);
            Object status=field(ReclazzAgent.class,null,"statusServer");
            FileWatcher watcher=(FileWatcher)field(status.getClass(),status,"doctorWatcher");
            // Complete the actual scan and then all queued imports, avoiding an
            // absence assertion based only on an arbitrary sleep interval.
            watcher.scanNow();
            var done=new CompletableFuture<Void>();
            ((ReloadQueue)field(ReclazzAgent.class,null,"reloadQueue")).submit("Synthetic import barrier",()->done.complete(null));
            done.get(10,TimeUnit.SECONDS);
            System.out.println("COUNT"+stage+"="+imports.get()); System.out.flush();
        }
    }
}
