package com.onurkat.reclazztest.batch;
import de.hybris.platform.core.PK;
import de.hybris.platform.core.model.ReclazzSchemaProbeModel;
import java.util.*;

/** Unedited recorder; cleanup never opens a measurement scope. */
public final class ProbeRemoveState {
    public static final class Scope {
        public final String run; public final int step; public final PK pk;
        private Scope(String r,int s,PK p) {run=r;step=s;pk=p;}
    }
    private static final ThreadLocal<Scope> ACTIVE=new ThreadLocal<>();
    private static String owner;
    private static final List<PK> TARGETS=new ArrayList<>();
    private static PK control;
    private static final Map<Integer,ReclazzSchemaProbeModel> MODELS=new LinkedHashMap<>();
    private static final Map<Integer,Map<String,Object>> CALLS=new LinkedHashMap<>();
    private static int scopes;
    private ProbeRemoveState() {}
    public static synchronized void own(String run) {
        if(owner!=null && !owner.equals(run)) throw new IllegalArgumentException("Foreign run");
    }
    public static synchronized void seed(String run,List<PK> targets,PK c) {
        own(run);if(owner!=null || targets.size()!=3 || new HashSet<>(targets).size()!=3 || targets.contains(c))
            throw new IllegalStateException("Invalid remove seed");
        owner=run;TARGETS.addAll(targets);control=c;
    }
    public static synchronized PK target(String run,int step) {own(run);if(step<0 || step>2 || TARGETS.size()!=3) throw new IllegalStateException("Not seeded");return TARGETS.get(step);}
    public static synchronized PK control(String run) {own(run);return control;}
    public static synchronized void begin(String run,int step) {
        own(run);if(ACTIVE.get()!=null || scopes!=0 || CALLS.containsKey(step)) throw new IllegalStateException("Conflicting remove scope");
        ACTIVE.set(new Scope(run,step,target(run,step)));scopes++;
    }
    public static Scope scope() {return ACTIVE.get();}
    public static synchronized void end() {if(ACTIVE.get()!=null) {ACTIVE.remove();scopes--;}}
    public static synchronized void record(Scope s,ReclazzSchemaProbeModel model,String version) {
        if(s!=ACTIVE.get()) throw new IllegalStateException("Wrong remove scope");
        var prior=MODELS.get(s.step);if(prior!=null && prior!=model) throw new IllegalStateException("Multiple remove models");
        int n=CALLS.containsKey(s.step)?((Number)CALLS.get(s.step).get("calls")).intValue():0;
        MODELS.put(s.step,model);CALLS.put(s.step,Map.of("calls",n+1,"version",version));
    }
    public static synchronized boolean recordedSame(String run,int step,ReclazzSchemaProbeModel model) {own(run);return MODELS.get(step)==model;}
    public static synchronized Map<String,Object> snapshot(String run) {own(run);var out=new LinkedHashMap<String,Object>();CALLS.forEach((k,v)->out.put(k.toString(),Map.copyOf(v)));return out;}
    public static synchronized int held() {return MODELS.size();}
    public static synchronized int scopes() {return scopes;}
    public static synchronized void clear(String run) {own(run);end();MODELS.clear();CALLS.clear();TARGETS.clear();control=null;owner=null;}
}
