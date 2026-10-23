/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class AddedVarHandleReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void freshAndRetainedHandlesShareStorageAndAdvertiseOnlyImplementedModes(boolean childLoader) throws Exception {
        var builder=WatchedApp.in(tmp).agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Bean",bean(0)).with("App","""
                    package app;
                    import java.lang.invoke.*;import java.lang.reflect.*;import java.util.*;
                    public class App {
                        static final Bean bean=new Bean();
                        static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
                        static void probe(VarHandle handle)throws Throwable{
                            Field field=Bean.class.getField("added");field.setInt(bean,41);
                            check((int)handle.get(bean)==41,"get shares reflection store");handle.set(bean,73);
                            check(field.getInt(bean)==73 && bean.read()==73,"set shares reflection and ordinary store");
                            check(handle.compareAndSet(bean,73,99),"CAS current value");
                            check(!handle.compareAndSet(bean,73,100) && field.getInt(bean)==99 && bean.read()==99,"CAS stale expected value");
                            check(handle.varType()==int.class && handle.coordinateTypes().equals(List.of(Bean.class)),"logical type and coordinate");
                            var supported=Set.of(VarHandle.AccessMode.GET,VarHandle.AccessMode.SET,VarHandle.AccessMode.COMPARE_AND_SET);
                            for(var mode:VarHandle.AccessMode.values())check(handle.isAccessModeSupported(mode)==supported.contains(mode),"accurate supported mode: "+mode);
                            check(handle.toMethodHandle(VarHandle.AccessMode.GET).invokeWithArguments(bean).equals(99),"mode MethodHandle uses same store");
                            check((int)handle.withInvokeExactBehavior().get(bean)==99,"exact invocation has logical receiver type");
                            try{handle.getAndAdd(bean,1);throw new AssertionError("unimplemented mode accepted");}catch(UnsupportedOperationException expected){}
                            try{handle.get((Bean)null);throw new AssertionError("null receiver accepted");}catch(NullPointerException expected){}
                            try{handle.get(new Object());throw new AssertionError("wrong receiver accepted");}catch(ClassCastException expected){}
                        }
                        public static void main(String[]args)throws Exception{
                            MethodHandles.lookup().unreflectVarHandle(Bean.class.getField("kept")).set(bean,5);
                            check(bean.kept==5,"physical VarHandle control");System.out.println("READY");VarHandle retained=null;
                            while(true){try{int stage=bean.version();if(stage>0){
                                VarHandle fresh=MethodHandles.lookup().unreflectVarHandle(Bean.class.getField("added"));
                                if(retained==null)retained=fresh;probe(fresh);probe(retained);System.out.println("OK:"+stage+":fresh-and-held:get-set-cas:mode-contract");
                            }}catch(Throwable failure){System.out.println("OBSERVED="+failure);failure.printStackTrace();}Thread.sleep(100);}
                        }
                    }
                    """);
        if(childLoader)builder.childClassLoader();
        try(var app=builder.start()) {
            app.awaitOrFail("READY","physical baseline");app.awaitOrFail("] Watching ","watcher ready");
            for(int stage=1;stage<=2;stage++){
                app.rewrite("Bean",bean(stage));
                app.awaitOrFail("OK:"+stage+":fresh-and-held:get-set-cas:mode-contract","fresh/held VarHandles remain usable across body saves");
            }
        }
    }
    private static String bean(int stage){return "package app;public class Bean {public int kept=41;public int version(){return "+stage+";}"+
            (stage==0?"public int read(){return kept;}":"public int added=41;public int read(){return added;}")+"}";}
}
