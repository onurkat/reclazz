package com.onurkat.reclazztest.batch;
import de.hybris.platform.core.PK;
import de.hybris.platform.core.model.ReclazzSchemaProbeModel;
import java.util.*;

/** Unedited bounded recorder. Reference equality, not hash identity, is the oracle. */
public final class ProbeLoadState {
    public static final class Scope {
        public final String run; public final int step; public final PK pk;
        private Scope(String run,int step,PK pk) {this.run=run;this.step=step;this.pk=pk;}
    }
    private static final ThreadLocal<Scope> ACTIVE=new ThreadLocal<>();
    private static String owner; private static int scopes;
    private static PK target,control;
    private static final Map<Integer,ReclazzSchemaProbeModel> MODELS=new LinkedHashMap<>();
    private static final Map<Integer,Map<String,Object>> CALLS=new LinkedHashMap<>();
    private ProbeLoadState() {}
    public static synchronized void own(String run) {
        if(owner!=null && !owner.equals(run)) throw new IllegalArgumentException("Foreign run");
    }
    public static synchronized void seed(String run,PK a,PK b) {
        own(run); if(target!=null) throw new IllegalStateException("Already seeded");
        owner=run;target=a;control=b;
    }
    public static synchronized PK target(String run) {own(run);if(target==null) throw new IllegalStateException("Not seeded");return target;}
    public static synchronized PK control(String run) {own(run);return control;}
    public static synchronized void begin(String run,int step) {
        own(run);if(ACTIVE.get()!=null || scopes!=0 || step < -1 || step > 2 || (step>=0 && CALLS.containsKey(step)))
            throw new IllegalStateException("Conflicting load scope");
        ACTIVE.set(new Scope(run,step,target(run)));scopes++;
    }
    public static Scope scope() {return ACTIVE.get();}
    public static synchronized void end() {if(ACTIVE.get()!=null) {ACTIVE.remove();scopes--;}}
    public static synchronized void record(Scope s,ReclazzSchemaProbeModel model,String version) {
        if(s!=ACTIVE.get() || s.step<0) throw new IllegalStateException("Warm lookup invoked callback");
        var previous=MODELS.get(s.step);
        if(previous!=null && previous!=model) throw new IllegalStateException("Multiple models in one load");
        int n=CALLS.containsKey(s.step)?((Number)CALLS.get(s.step).get("calls")).intValue():0;
        MODELS.put(s.step,model);CALLS.put(s.step,Map.of("calls",n+1,"version",version));
    }
    public static synchronized ReclazzSchemaProbeModel model(String run,int step) {own(run);var m=MODELS.get(step);if(m==null) throw new IllegalStateException("Missing load");return m;}
    public static synchronized ReclazzSchemaProbeModel recorded(String run,int step) {own(run);return MODELS.get(step);}
    public static synchronized boolean distinct(String run,int step,ReclazzSchemaProbeModel model) {own(run);return MODELS.entrySet().stream().noneMatch(e->e.getKey()!=step && e.getValue()==model);}
    public static synchronized Map<String,Object> snapshot(String run) {own(run);var out=new LinkedHashMap<String,Object>();CALLS.forEach((k,v)->out.put(k.toString(),Map.copyOf(v)));return out;}
    public static synchronized int held() {return MODELS.size();}
    public static synchronized int scopes() {return scopes;}
    public static synchronized void clear(String run) {own(run);end();MODELS.clear();CALLS.clear();target=null;control=null;owner=null;}
}
