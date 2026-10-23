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

class AddedConstructorBoundaryReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void preSuperCaptureAndHeldConstructorsPreservePhysicalParentOrder(boolean childLoader) throws Exception {
        var builder=WatchedApp.in(tmp).agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Parent","""
                    package app; public class Parent {
                        public static int calls; public final String seen;
                        public Parent(){calls++;seen=observe();if(seen.equals("parent-fail"))throw new IllegalStateException("parent");}
                        public String observe(){return "parent";}
                    }
                    """).with("Outer",outer(0)).with("App","""
                    package app;
                    import java.lang.reflect.*;import java.lang.invoke.*;
                    public class App {
                        static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
                        public static void main(String[]args)throws Exception {
                            Outer outer=new Outer();Outer.Inner held=outer.new Inner();
                            check(held.owner()==outer && held.seen.equals("owner"),"native capture baseline");
                            System.out.println("READY="+held.value);
                            Constructor<?> retained=null;MethodHandle handle=null;
                            while(true){try{
                                int stage=held.version();if(stage==0){Thread.sleep(100);continue;}
                                if(retained==null){retained=Outer.Inner.class.getConstructor(Outer.class,String.class);handle=MethodHandles.lookup().findConstructor(Outer.Inner.class,MethodType.methodType(void.class,Outer.class,String.class));}
                                int before=Parent.calls;
                                Outer.Inner made=(Outer.Inner)retained.newInstance(outer,"primary");
                                check(Parent.calls==before+1 && made.seen.equals("owner") && made.owner()==outer,"capture before physical parent, same receiver, parent once");
                                check(made.value.equals("primary"+stage+":primary"),"held reflection follows body save");
                                before=Parent.calls;made=(Outer.Inner)handle.invokeWithArguments(outer,"lookup");
                                check(Parent.calls==before+1 && made.seen.equals("owner") && made.owner()==outer,"held lookup constructor preserves parent/capture");
                                check(made.value.equals("primary"+stage+":lookup"),"held lookup follows body save");
                                before=Parent.calls;made=(Outer.Inner)Outer.Inner.class.getConstructor(Outer.class,long.class).newInstance(outer,7L);
                                check(Parent.calls==before+1 && made.value.equals("delegate"+stage+":7") && made.owner()==outer,"existing this delegation once");
                                outer.ownerValue="parent-fail";before=Parent.calls;
                                try{retained.newInstance(outer,"bad");throw new AssertionError("missing reflection target exception");}
                                catch(InvocationTargetException failure){check(failure.getCause() instanceof IllegalStateException,"reflection wraps parent exception once");}
                                check(Parent.calls==before+1,"failing reflection parent once");before=Parent.calls;
                                try{handle.invokeWithArguments(outer,"bad");throw new AssertionError("missing handle target exception");}
                                catch(IllegalStateException failure){check(failure.getMessage().equals("parent"),"handle propagates raw parent exception");}
                                check(Parent.calls==before+1,"failing handle parent once");outer.ownerValue="owner";
                                System.out.println("OK:"+stage+":capture-before-parent:parent-once:held-reflection-and-lookup:exception-shape");
                            }catch(Throwable failure){outer.ownerValue="owner";System.out.println("OBSERVED="+failure);failure.printStackTrace();}Thread.sleep(100);}
                        }
                    }
                    """);
        if(childLoader)builder.childClassLoader();
        try(var app=builder.start()) {
            app.awaitOrFail("READY=initial","inner class loaded before edit");app.awaitOrFail("] Watching ","watcher ready");
            for(int stage=1;stage<=2;stage++) {
                app.rewrite("Outer",outer(stage));
                app.awaitOrFail("OK:"+stage+":capture-before-parent:parent-once:held-reflection-and-lookup:exception-shape",
                        "physical parent and held constructors preserve capture, delegation and failures across saves");
            }
        }
    }
    private static String outer(int stage){return "package app;public class Outer {public String ownerValue=\"owner\";public class Inner extends Parent {public String observe(){return Outer.this.ownerValue;}public Outer owner(){return Outer.this;}public int version(){return "+stage+";}public String value=\"initial\";public Inner(){}"+
            (stage==0?"":"public Inner(String value){this.value=\"primary"+stage+":\"+value;} public Inner(long value){this();this.value=\"delegate"+stage+":\"+value;}")+"}}";}
}
