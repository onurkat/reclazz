/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.transform.*;
import com.onurkat.reclazz.ui.Failures;
import java.lang.instrument.*;
import java.lang.invoke.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Stable physical admission followed by one record/caller CAS. The engine still owns the S readiness gate. */
public final class RecordStockBatch implements RecordBatch {
    private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private final Instrumentation instrumentation;
    private final RecordNativeBatch.Scope scope;
    private final String refusal;
    private final RecordSchema.Preparation preparation;
    private final ClassDefinition[] physical;
    private RecordNativeBatch.Outcome outcome;
    private boolean closed;
    private RecordStockBatch(Instrumentation instrumentation,RecordNativeBatch.Scope scope,String refusal,RecordSchema.Preparation preparation,ClassDefinition[] physical) {
        this.instrumentation=instrumentation;this.scope=scope;this.refusal=refusal;this.preparation=preparation;this.physical=physical;
    }
    public boolean active(){return !scope.records().isEmpty();}
    public Set<String> names(){return scope.names();}
    public Set<String> recordNames(){return scope.records();}
    public List<RecordNativeBatch.Change> changes(){return scope.changes();}
    public synchronized Map<String,String> refusals() {
        String reason=refusal;
        if(reason==null&&outcome!=null&&!outcome.committed())reason=outcome.reason();
        if(reason==null)return Map.of();
        var values=new LinkedHashMap<String,String>();for(String name:names())values.put(name,reason);return Map.copyOf(values);
    }
    public static RecordStockBatch prepare(Map<String,byte[]> incoming,Class<?>[] census,Predicate<String> watched,Instrumentation instrumentation,TransformContext context,int version,Set<String> repairPending) {
        LookupCapture.requireTrustedCaller(WALKER.getCallerClass(),"preparing stock record cohort");
        var scope=RecordNativeBatch.stockScope(incoming,census,watched,instrumentation,repairPending);
        String refusal=scope.refusals().isEmpty()?null:scope.refusals().values().iterator().next();
        if(refusal==null&&scope.records().size()>1)refusal="stock record cohort requires unproven overlapping record publication; restart to apply";
        if(scope.records().isEmpty()||refusal!=null)return new RecordStockBatch(instrumentation,scope,refusal,null,new ClassDefinition[0]);
        RecordSchema.Preparation pending=null;boolean transferred=false;
        try {
            var definitions=new ArrayList<ClassDefinition>();
            var lookups=new IdentityHashMap<Class<?>,MethodHandles.Lookup>();
            Class<?> record=null;byte[] recordBytes=null;
            for(var change:scope.changes()) {
                byte[] stable=PhysicalDefinitions.get(change.owner());
                if(stable==null){
                    if(!scope.records().contains(change.name())&&!RecordCohortCandidates.retain(change.owner().getClassLoader(),change.name().replace('.','/')))
                        throw new IllegalArgumentException("caller was not prepared for record cohorts at initial load: "+change.name()+"; restart to apply");
                    throw new IllegalArgumentException("complete physical definition unavailable: "+change.name());
                }
                MethodHandles.Lookup lookup=ownLookup(change.owner());
                lookups.put(change.owner(),lookup);definitions.add(new ClassDefinition(change.owner(),stable));
                if(scope.records().contains(change.name())){record=change.owner();recordBytes=change.bytes();}
            }
            if(record==null)throw new IllegalArgumentException("record owner unavailable");
            var slot=RecordSchema.slot(record);var previous=slot.current();
            var schema=RecordCompanion.prepare(record,lookups.get(record),recordBytes,context,version);
            pending=slot.prepareVirtual(schema.snapshot().members());pending.stageDispatch(record,schema.snapshot().targets());
            for(var change:scope.changes()) {
                if(change.owner()==record)continue;
                var caller=RecordCallerCompanion.prepare(change.owner(),lookups.get(change.owner()),change.bytes(),context,version);
                pending.stageCallerMembers(change.owner(),caller.members());pending.stageDispatch(change.owner(),caller.targets());pending.stageFieldInitializers(change.owner(),caller.fieldInitializers());
            }
            if(slot.current()!=previous)throw new IllegalStateException("record epoch changed during preparation");
            var prepared=new RecordStockBatch(instrumentation,scope,null,pending,definitions.toArray(ClassDefinition[]::new));
            transferred=true;return prepared;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure) {
            return new RecordStockBatch(instrumentation,scope,"stock record preparation refused: "+Failures.describe(failure)+"; restart to apply",null,new ClassDefinition[0]);
        }finally{if(!transferred&&pending!=null)pending.close();}
    }
    private static MethodHandles.Lookup ownLookup(Class<?> owner)throws IllegalAccessException {
        MethodHandles.Lookup lookup=LookupCapture.get(owner);
        if(lookup==null)for(var field:ReflectionBridge.physicalDeclaredFields(owner))
            if(field.getName().equals(InjectedNames.LOOKUP_FIELD)&&field.getType()==MethodHandles.Lookup.class
                    &&java.lang.reflect.Modifier.isStatic(field.getModifiers())&&field.isSynthetic()) {
                field.setAccessible(true);lookup=(MethodHandles.Lookup)field.get(null);break;
            }
        if(lookup==null||lookup.lookupClass()!=owner||!lookup.hasFullPrivilegeAccess())
            throw new IllegalArgumentException("own initialized lookup unavailable: "+owner.getName());
        LookupCapture.store(owner,lookup);return lookup;
    }

    /** A later save preflight can refuse the complete staged cohort; never apply a reduced subset. */
    public synchronized RecordStockBatch requireComplete(Set<String> accepted) {
        LookupCapture.requireTrustedCaller(WALKER.getCallerClass(),"checking stock record cohort");
        if(outcome!=null||closed)throw new IllegalStateException("Stock record preparation is closed");
        if(accepted.containsAll(names()))return this;
        var missing=new TreeSet<>(names());missing.removeAll(accepted);
        closePrepared();outcome=new RecordNativeBatch.Outcome(false,false,"stock record cohort preflight rejected "+String.join(", ",missing)+"; no cohort member was redefined; restart to apply",Map.of());return this;
    }
    public synchronized RecordNativeBatch.Outcome commit(Consumer<RecordNativeBatch.Change> repair) {
        LookupCapture.requireTrustedCaller(WALKER.getCallerClass(),"committing stock record cohort");
        if(outcome!=null)return outcome;
        if(closed)throw new IllegalStateException("Stock record preparation is closed");
        if(!active()||refusal!=null){closePrepared();return outcome=new RecordNativeBatch.Outcome(false,false,refusal==null?"not a record shape cohort":refusal,Map.of());}
        try {
            PhysicalDefinitions.redefine(instrumentation,physical);
            if(!preparation.commit())return outcome=new RecordNativeBatch.Outcome(true,false,"stock record activation refused stale epoch; prepared logical route discarded",Map.of());
            // Nothing after the CAS can turn installed code into a rollback claim.
            outcome=new RecordNativeBatch.Outcome(true,true,"stock record cohort admitted",Map.of());
            var failures=new LinkedHashMap<String,String>();
            for(var change:changes()) {
                if(recordNames().contains(change.name()))try {
                    if(!KeepBaseline.recordVirtualSchemaApplied(change.owner(),change.bytes()))throw new IllegalStateException("logical record constructor receipt failed");
                }catch(RuntimeException|LinkageError failure){failures.put("logical constructor receipt "+change.name(),Failures.describe(failure));}
                try{repair.accept(change);}catch(RuntimeException|LinkageError failure){failures.put(change.name(),Failures.describe(failure));}
            }
            return outcome=new RecordNativeBatch.Outcome(true,true,"stock record cohort admitted",failures);
        }catch(Exception|LinkageError failure) {
            if(outcome!=null&&outcome.committed())throw new IllegalStateException("Committed record repair escaped its surface boundary",failure);
            return outcome=new RecordNativeBatch.Outcome(true,false,"stock record native admission refused: "+Failures.describe(failure),Map.of());
        }finally{closePrepared();}
    }
    private void closePrepared(){if(preparation!=null)preparation.close();closed=true;}
    @Override public synchronized void close(){LookupCapture.requireTrustedCaller(WALKER.getCallerClass(),"discarding stock record cohort");closePrepared();}
}
