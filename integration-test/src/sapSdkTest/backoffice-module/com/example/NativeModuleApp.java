/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.example;
import java.nio.file.*;
import java.lang.reflect.*;
import java.lang.instrument.*;
import java.util.*;
import com.hybris.cockpitng.core.persistence.packaging.WidgetClassLoader;
import com.onurkat.reclazz.agent.*;
import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.transform.*;
import com.onurkat.reclazz.compiler.*;
import com.onurkat.reclazz.config.AgentConfig;
public class NativeModuleApp {
 static Object field(Object o,String name)throws Exception{
  Class<?> type=o instanceof Class<?>?(Class<?>)o:o.getClass();
  Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(o instanceof Class<?>?null:o);
 }
 static void put(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
 public static void main(String[]args)throws Exception{
  Path root=Path.of(args[0]),sdk=Path.of(args[1]),ext=root.resolve("extension");
  Files.createDirectories(ext.resolve("src"));Files.createDirectories(ext.resolve("classes"));
  Files.createDirectories(ext.resolve("backoffice/src"));Files.createDirectories(ext.resolve("backoffice/classes"));
  HybrisContext context=new HybrisContext(sdk);
  put(context,"platformHome",sdk.resolve("bin/platform"));
  ExtensionInfo module=new ExtensionInfo("example",ext,List.of("backoffice"),true,false,true,true);
  ExtensionInfo backoffice=new ExtensionInfo("backoffice",sdk.resolve("bin/modules/backoffice-framework/backoffice"),List.of(),true,true,false);
  put(context,"extensions",new LinkedHashMap<>(Map.of("example",module,"backoffice",backoffice)));
  HybrisPlatformContext platform=new HybrisPlatformContext(sdk,AgentConfig.parse(""));put(platform,"hybrisContext",context);
  boolean sources=platform.getSourceDirs().get("example").contains(ext.resolve("backoffice/src"));
  boolean outputs=platform.getClassOutputDirs().get("example").contains(ext.resolve("backoffice/classes"));
  String cp=new ClasspathResolver(context).resolve();
  boolean cockpit=Arrays.stream(cp.split(java.io.File.pathSeparator)).anyMatch(x->Path.of(x).getFileName().toString().startsWith("cockpitframework-"));
  IncrementalCompiler compiler=new IncrementalCompiler(cp,context);
  Method groups=IncrementalCompiler.class.getDeclaredMethod("groups",String.class,List.class);groups.setAccessible(true);
  Map<?,?> target=(Map<?,?>)groups.invoke(compiler,"example",List.of(ext.resolve("backoffice/src/com/example/ModuleController.java")));
  System.out.println("ROOTS source="+sources+" output="+outputs+" name="+platform.resolveClassName(ext.resolve("backoffice/classes/com/example/ModuleController.class"))+" compilerOutput="+(target.containsKey(ext.resolve("backoffice/classes"))?"backoffice-classes":"core-classes")+" globalCockpitClasspath="+cockpit);
  Path controller=ext.resolve("backoffice/src/com/example/CompileController.java");
  Files.createDirectories(controller.getParent());
  Files.writeString(controller,"package com.example; public class CompileController extends com.hybris.cockpitng.util.DefaultWidgetController {}");
  IncrementalCompiler.CompileResult compiled=compiler.compile(controller,"example");
  if(!compiled.isSuccess())throw new AssertionError("native cockpit compile: "+compiled.getErrors());
  if(!Files.isRegularFile(ext.resolve("backoffice/classes/com/example/CompileController.class")))throw new AssertionError("native output routing");
  System.out.println("AUTOCOMPILE nativeCockpitType=true output=backoffice-classes");
  Instrumentation inst=(Instrumentation)field(ReclazzAgent.class,"instrumentation");
  long end=System.nanoTime()+20_000_000_000L;TransformContext transform;
  while((transform=(TransformContext)field(ReclazzAgent.class,"transformContext"))==null&&System.nanoTime()<end)Thread.sleep(20);
  if(transform==null)throw new AssertionError("agent startup timeout");
  Files.createDirectories(ext.resolve("backoffice/classes/com/example"));
  Files.copy(root.resolve("baseline/com/example/ModuleController.class"),ext.resolve("backoffice/classes/com/example/ModuleController.class"),StandardCopyOption.REPLACE_EXISTING);
  transform=new TransformContext();transform.populateFromPlatformContext(platform);
  ReclazzTransformer preparedTransformer=new ReclazzTransformer(transform,AgentConfig.parse(""));
  inst.addTransformer(preparedTransformer,true);
  try(WidgetClassLoader loader=new WidgetClassLoader(NativeModuleApp.class.getClassLoader(),root.resolve("deployed").toString(),false)){
   Class<?> type=Class.forName("com.example.ModuleController",true,loader);
   System.out.println("NATIVE_LOADER type="+type.getClassLoader().getClass().getName()+" codeSourceLocationNull="+(type.getProtectionDomain().getCodeSource()==null||type.getProtectionDomain().getCodeSource().getLocation()==null)+" modifiable="+inst.isModifiableClass(type)+" watched="+transform.isWatched("com/example/ModuleController")+" prepared="+(transform.getMetadata("com/example/ModuleController")!=null));
   if(!"one".equals(type.getMethod("marker").invoke(null)))throw new AssertionError("baseline");
   ClassReloader reloader=new ClassReloader(inst);
   ClassReloader.ReloadResult body=reloader.reload(type.getName(),Files.readAllBytes(root.resolve("body/com/example/ModuleController.class")));
   Object observed=type.getMethod("marker").invoke(null);
   System.out.println("BODY success="+body.isSuccess()+" observed="+observed);
   if(!body.isSuccess()||!"two".equals(observed))throw new AssertionError("body reload");
   com.onurkat.reclazz.reload.StructuralReloader structural=new com.onurkat.reclazz.reload.StructuralReloader(inst,transform,AgentConfig.parse(""),null);
   structural.setTransformer(preparedTransformer);
   ClassReloader.ReloadResult added=structural.reload(type.getName(),Files.readAllBytes(root.resolve("added/com/example/ModuleController.class")));
   System.out.println("ADDED success="+added.isSuccess()+" structural="+added.isStructuralChange());
   Object after=type.getMethod("marker").invoke(null);
   System.out.println("ADDED_CALL observed="+after);
   if(!added.isSuccess()||!"new".equals(after))throw new AssertionError("prepared stock JDK member reload");
  }
 }
}
