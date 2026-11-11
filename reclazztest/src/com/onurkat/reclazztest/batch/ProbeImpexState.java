package com.onurkat.reclazztest.batch;

import de.hybris.platform.core.Registry;
import de.hybris.platform.core.PK;
import de.hybris.platform.jalo.Item;
import de.hybris.platform.jalo.JaloSession;
import de.hybris.platform.jalo.user.UserManager;
import de.hybris.platform.impex.jalo.imp.ImpExImportReader;
import de.hybris.platform.impex.jalo.header.StandardColumnDescriptor;
import de.hybris.platform.tx.Transaction;
import java.util.*;
import java.util.concurrent.*;

/** Unedited recorder. Native readers and their session belong to one bounded worker. */
public final class ProbeImpexState {
    public static final List<String> ROLES=List.of("translator-warm","translator-fresh","decorator-warm","decorator-fresh","control");
    private static Run active;
    private ProbeImpexState() { }
    public static String validate(String run) {
        if(!run.startsWith("reclazz-impex-"))throw new IllegalArgumentException("Invalid run");
        if(!UUID.fromString(run.substring(14)).toString().equals(run.substring(14)))throw new IllegalArgumentException("Invalid UUID");
        return run;
    }
    public static String token(String run){return validate(run)+":raw";}
    public static String german(String run){return validate(run)+":ordinary";}
    public static String script(String run,String kind,String role,int lines) {
        validate(run);if(!List.of("translator","decorator").contains(kind)||!ROLES.contains(role)||lines<1||lines>8)throw new IllegalArgumentException("Invalid import scope");
        String modifier=kind.equals("translator")?"translator=com.onurkat.reclazztest.batch.ProbeImpexValueTranslator":"cellDecorator=com.onurkat.reclazztest.batch.ProbeImpexCellDecorator";
        return "UPDATE Title;code[unique=true];name[lang=en,"+modifier+"];name[lang=de]\n"
            +(";"+run+"-"+role+";"+token(run)+";"+german(run)+"\n").repeat(lines);
    }
    public static synchronized void observed(Object handler,String kind,String raw,String result) {
        if(active==null||!token(active.run).equals(raw))throw new IllegalStateException("Foreign native callback");
        active.events.add(new Event(handler,kind,raw,result,Thread.currentThread()));
    }
    public static synchronized void begin(String run,Map<String,PK> pks) {
        validate(run);if(active!=null)throw new IllegalStateException("Another probe is active");
        if(!pks.keySet().equals(new HashSet<>(ROLES)))throw new IllegalArgumentException("Incomplete PKs");
        active=new Run(run,Map.copyOf(pks));active.worker.start();
    }
    private static synchronized Run require(String run) {
        validate(run);if(active==null||!active.run.equals(run))throw new IllegalStateException("Wrong active run");return active;
    }
    public static Map<String,Object> advance(String run) throws Exception {
        Run r=require(run);Command c=new Command();
        if(!r.worker.isAlive()||!r.commands.offer(c))throw new IllegalStateException("Worker not available: "+r.failure);
        return c.reply.get(30,TimeUnit.SECONDS);
    }
    public static Map<String,Object> snapshot(String run) {
        Run r=require(run);synchronized(ProbeImpexState.class) {
            List<Map<String,Object>> events=new ArrayList<>();
            for(Event e:r.events)events.add(Map.of("kind",e.kind,"raw",e.raw,"result",e.result,
                "warm",r.handlers.containsValue(e.handler),"worker",e.thread==r.worker,
                "identity",System.identityHashCode(e.handler)));
            return Map.of("run",run,"workerAlive",r.worker.isAlive(),"steps",r.steps,
                "failure",r.failure==null?"":r.failure,"events",events,"readers",r.readers.size());
        }
    }
    public static void close(String run) throws Exception {
        Run r=require(run);r.stopping=true;r.commands.offer(new Command());r.worker.join(35000);
        if(r.worker.isAlive())throw new IllegalStateException("Native import worker still alive; do not delete its rows");
        if(r.failure!=null)throw new IllegalStateException("Worker cleanup failed: "+r.failure);
    }
    public static synchronized void forget(String run) {
        Run r=require(run);if(r.worker.isAlive()||!r.readers.isEmpty())throw new IllegalStateException("Worker/readers not closed");
        active=null;
    }
    public static synchronized boolean idle(){return active==null;}
    private record Event(Object handler,String kind,String raw,String result,Thread thread) { }
    private static class Command { final CompletableFuture<Map<String,Object>> reply=new CompletableFuture<>(); }
    private static class Warm {
        final ImpExImportReader reader;Object header,column,handler;
        Warm(ImpExImportReader reader){this.reader=reader;}
    }
    private static class Run {
        final String run;final Map<String,PK> pks;
        final BlockingQueue<Command> commands=new ArrayBlockingQueue<>(2);
        final List<Warm> readers=new ArrayList<>();final Map<String,Object> handlers=new HashMap<>();final List<Event> events=new ArrayList<>();
        final Thread worker;volatile boolean stopping;volatile String failure;volatile int steps;
        Run(String run,Map<String,PK> pks){this.run=run;this.pks=pks;worker=new Thread(this::work,"reclazz-impex-probe");worker.setDaemon(true);}
        void work() {
            JaloSession session=null;Command current=null;
            try {
                Registry.activateMasterTenantAndFailIfAlreadySet();session=JaloSession.getCurrentSession();
                session.setUser(UserManager.getInstance().getAdminEmployee());
                synchronized(ProbeImpexState.class) {
                    for(String kind:List.of("translator","decorator"))readers.add(new Warm(new ImpExImportReader(script(run,kind,kind+"-warm",8))));
                }
                while(!stopping) {
                    current=commands.poll(180,TimeUnit.SECONDS);
                    if(current==null)throw new IllegalStateException("Probe lease expired");
                    if(stopping)break;
                    if(Transaction.current().isRunning())throw new IllegalStateException("Transaction retained while idle");
                    Map<String,Object> result=new LinkedHashMap<>();int i=0;
                    for(String kind:List.of("translator","decorator")) {
                        Warm w=readers.get(i++);int count=w.reader.getProcessedItemsCount();
                        Item item=(Item)w.reader.readLine();
                        if(item==null||!pks.get(kind+"-warm").equals(item.getPK())||w.reader.getProcessedItemsCount()!=count+1||w.reader.getDumpedLineCount()!=0)
                            throw new IllegalStateException("Native import line/PK mismatch");
                        Object h=w.reader.getCurrentHeader();Object col=w.reader.getCurrentHeader().getColumns().stream()
                            .filter(c->kind.equals("translator")?c instanceof StandardColumnDescriptor s&&s.getValueTranslator() instanceof ProbeImpexValueTranslator:c.getCSVCellDecorator() instanceof ProbeImpexCellDecorator)
                            .findFirst().orElseThrow();
                        Object handler=kind.equals("translator")?((StandardColumnDescriptor)col).getValueTranslator():((de.hybris.platform.impex.jalo.header.AbstractColumnDescriptor)col).getCSVCellDecorator();
                        synchronized(ProbeImpexState.class) {
                            if(w.header==null){w.header=h;w.column=col;w.handler=handler;handlers.put(kind,handler);}
                            if(w.header!=h||w.column!=col||w.handler!=handler)throw new IllegalStateException("Warm native ownership changed");
                        }
                        if(Transaction.current().isRunning())throw new IllegalStateException("Native import left transaction open");
                        result.put(kind,Map.of("sameReferences",true,"worker",Thread.currentThread()==worker,"processed",w.reader.getProcessedItemsCount(),"dumped",w.reader.getDumpedLineCount(),"pk",item.getPK().toString()));
                    }
                    steps++;result.put("run",run);result.put("step",steps);current.reply.complete(result);current=null;
                }
            } catch(Throwable problem) {failure=problem.toString();if(current!=null)current.reply.completeExceptionally(problem);}
            finally {
                for(Warm w:readers)try{w.reader.close();}catch(Throwable e){failure="reader close: "+e;}
                synchronized(ProbeImpexState.class) {readers.clear();handlers.clear();}
                try{if(session!=null)session.close();}catch(Throwable e){failure="session close: "+e;}
                finally{Registry.unsetCurrentTenant();}
                for(Command c:commands)c.reply.completeExceptionally(new IllegalStateException("Worker closed"));commands.clear();
            }
        }
    }
}
