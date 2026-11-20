/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Serial CONFIG transactions; all effective evidence belongs to one Runtime revision. */
final class LiveConfigController implements AutoCloseable {
    @FunctionalInterface interface Transition {
        CompletionStage<Void> apply(String key, AgentConfig.LiveOptions target, Runnable publish);
    }
    @FunctionalInterface interface RuntimeTransition {
        CompletionStage<Void> apply(String key, AgentConfig.Runtime target, AgentConfig.Publication publish);
    }
    private final AgentConfig config;
    private final boolean sap,scopeAvailable;
    private final Set<String> extensions;
    private final RuntimeTransition transition;
    private final long acknowledgementMillis;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Reclazz-LiveConfig");t.setDaemon(true);return t;});
    private final AtomicLong serial=new AtomicLong();
    LiveConfigController(AgentConfig config,boolean sap,Transition transition){this(config,sap,transition,10000);}
    LiveConfigController(AgentConfig config,boolean sap,Transition transition,long acknowledgementMillis){
        this(config,sap,Set.of(),false,(key,target,publish)->transition.apply(key,target.options(),()->publish.accept(target)),acknowledgementMillis);
    }
    LiveConfigController(AgentConfig config,PlatformContext platform,RuntimeTransition transition){
        this(config,platform.getPlatformId()==PlatformContext.Platform.HYBRIS,
                platform instanceof HybrisPlatformContext h?Set.copyOf(h.getHybrisContext().getExtensions().keySet()):Set.of(),
                platform instanceof HybrisPlatformContext,transition,10000);
    }
    LiveConfigController(AgentConfig config,Set<String> extensions,RuntimeTransition transition){this(config,true,extensions,true,transition,10000);}
    private LiveConfigController(AgentConfig config,boolean sap,Set<String> extensions,boolean scopeAvailable,RuntimeTransition transition,long acknowledgementMillis){
        this.config=Objects.requireNonNull(config);this.sap=sap;this.extensions=Set.copyOf(extensions);this.scopeAvailable=scopeAvailable;
        this.transition=Objects.requireNonNull(transition);this.acknowledgementMillis=acknowledgementMillis;
    }
    String reply(String command){return reply(command,acknowledgementMillis,null);}
    String reply(String command,Consumer<String> pendingAndTerminal){return reply(command,acknowledgementMillis,pendingAndTerminal);}
    String reply(String command,long timeoutMillis){return reply(command,timeoutMillis,null);}
    private String reply(String command,long timeoutMillis,Consumer<String> pendingAndTerminal){
        String[] parts=command.strip().split("\\s+");
        if(parts.length<3 || !parts[2].matches("[A-Za-z0-9_-]{1,64}"))return null;
        String id=parts[2];
        if(parts[1].equals("GET"))return parts.length==3?state(id,"OK"):error(id,"bad_request","GET takes only an id");
        if(!parts[1].equals("SET"))return error(id,"bad_request","Expected GET or SET");
        var answer=new CompletableFuture<String>();
        try {executor.execute(()->{try{answer.complete(set(id,parts));}catch(Throwable failure){answer.complete(error(id,"failed",detail(failure)));}});}
        catch(RejectedExecutionException stopped){return error(id,"unavailable","Agent settings are stopping");}
        try{return answer.get(timeoutMillis,TimeUnit.MILLISECONDS);}
        catch(TimeoutException pending){
            String message="CONFIG "+id+" PENDING rev="+config.runtime().revision();
            if(pendingAndTerminal==null)return message;
            pendingAndTerminal.accept(message);answer.thenAccept(pendingAndTerminal);return null;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return "CONFIG "+id+" PENDING rev="+config.runtime().revision();}
        catch(ExecutionException failure){return error(id,"failed",detail(failure));}
    }
    private String set(String id,String[] parts){
        if(parts.length<5 || parts.length>11 || !parts[3].matches("base=[0-9]+"))return error(id,"bad_request","SET needs base and one to seven options");
        long base;try{base=Long.parseLong(parts[3].substring(5));}catch(NumberFormatException overflow){return error(id,"bad_request","Invalid revision");}
        Map<String,Boolean> changes=new LinkedHashMap<>();Set<String> seen=new HashSet<>();String watch=null;
        for(int i=4;i<parts.length;i++){
            String[] pair=parts[i].split("=",-1);
            if(pair.length!=2 || !seen.add(pair[0]))return error(id,"bad_request","Duplicate or malformed option");
            if(pair[0].equals("watchExtensions")){watch=pair[1];continue;}
            if(!AgentConfig.LiveOptions.KEYS.contains(pair[0]))return error(id,"unknown_key","Unknown option");
            if(!pair[1].equals("true") && !pair[1].equals("false"))return error(id,"bad_request","Options must be true or false");
            if(!sap && !pair[0].equals("autoCompile"))return error(id,"unavailable","SAP option is unavailable on this platform");
            changes.put(pair[0],Boolean.valueOf(pair[1]));
        }
        var previous=config.runtime();var scope=previous.scope();
        if(watch!=null){
            if(!sap)return error(id,"unavailable","Watch scope is available only on SAP Commerce");
            if(!scopeAvailable || scope.all() || scope.names().contains("*") || Arrays.stream(watch.split(";",-1)).anyMatch("*"::equals))
                return error(id,"restart_required","Watch-all transitions require a restart");
            if(watch.length()>3500)return error(id,"bad_request","Watch scope exceeds the bounded command size");
            Set<String> names=new TreeSet<>();for(String name:watch.split(";",-1))if(!name.isBlank())names.add(name);
            if(!extensions.containsAll(names))return error(id,"unknown_extension","Watch scope contains an undiscovered extension");
            scope=new AgentConfig.WatchScope(false,names);
        }
        if(base!=previous.revision())return state(id,"CONFLICT");
        var target=new AgentConfig.Runtime(previous.revision()+1,previous.options().with(changes),scope,previous.stats(),previous.inventory());
        AgentConfig.Publication publish=(next,install)->{
            if(next.revision()!=target.revision() || !next.options().equals(target.options()) || !next.scope().equals(target.scope()))
                throw new IllegalArgumentException("Staged Runtime does not match requested revision");
            config.publishRuntime(previous,next,install);
        };
        if(target.options().autoCompile()!=previous.options().autoCompile() || !scope.equals(previous.scope())){
            try{transition.apply("config-"+serial.incrementAndGet(),target,publish).toCompletableFuture().get();}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CompletionException(interrupted);}
            catch(ExecutionException failure){throw new CompletionException(failure.getCause());}
        }else publish.accept(target);
        return state(id,"OK");
    }
    private String state(String id,String status){
        var value=config.runtime();var fields=new StringBuilder("CONFIG ").append(id).append(' ').append(status).append(" rev=").append(value.revision());
        value.options().values().forEach((key,enabled)->fields.append(' ').append(key).append('=').append(enabled));
        var live=new ArrayList<>(sap?AgentConfig.LiveOptions.KEYS:List.of("autoCompile"));
        if(scopeAvailable && !value.scope().all() && !value.scope().names().contains("*"))live.add("watchExtensions");
        fields.append(" live=").append(String.join(",",live));
        fields.append(" watchExtensions.count=").append(watchNames(value).size()).append(" watchExtensions.sha256=").append(watchHash(value));
        fields.append(" loadedUninstrumented=").append(value.stats().loadedUninstrumented()).append(" unwatchable=").append(value.stats().unwatchable());
        return fields.toString();
    }
    private static List<String> watchNames(AgentConfig.Runtime value){return value.scope().all()?List.of("*"):value.scope().names().stream().filter(name->!name.isBlank()).sorted().toList();}
    private static String watchHash(AgentConfig.Runtime value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join(";",watchNames(value)).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    String doctorOptions(){
        var value=config.runtime();var fields=new StringBuilder("{\"rev\":").append(value.revision());
        value.options().values().forEach((key,enabled)->fields.append(",\"").append(key).append("\":").append(enabled));
        fields.append(",\"loadedUninstrumented\":").append(value.stats().loadedUninstrumented()).append(",\"unwatchable\":").append(value.stats().unwatchable());
        List<String> names=watchNames(value);
        fields.append(",\"watchExtensions\":{\"count\":").append(names.size()).append(",\"sha256\":\"").append(watchHash(value)).append("\",\"sample\":[");
        for(int i=0;i<Math.min(5,names.size());i++){
            if(i>0)fields.append(',');String name=names.get(i);if(name.length()>40)name=name.substring(0,40);
            fields.append('"').append(StatusServer.escapeJson(name)).append('"');
        }
        return fields.append("],\"truncated\":").append(names.size()>5 || names.stream().anyMatch(name->name.length()>40)).append("}}").toString();
    }
    private static String detail(Throwable failure){
        while(failure.getCause()!=null && (failure instanceof CompletionException || failure instanceof ExecutionException))failure=failure.getCause();
        String text=String.valueOf(failure.getMessage()).replace('\n',' ').replace('\r',' ');return text.substring(0,Math.min(200,text.length()));
    }
    private static String error(String id,String code,String reason){return "CONFIG "+id+" ERROR "+code+" "+reason;}
    @Override public void close(){executor.shutdownNow();}
}
