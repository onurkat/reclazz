/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.compiler;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class BackofficeModuleCompilationTest {
    @TempDir Path home;
    private Path extension(String name, boolean module, String dependency) throws Exception {
        Path root=home.resolve("bin/custom/"+name);
        Files.createDirectories(root.resolve("src/com/example"));
        Files.createDirectories(root.resolve("backoffice/src/com/example"));
        Files.createDirectories(root.resolve("backoffice/classes"));
        Files.writeString(root.resolve("extensioninfo.xml"),"<extensioninfo><extension name='"+name+"'>"
                +"<meta key='backoffice-module' value='"+module+"'/>"
                +(dependency==null?"":"<requires-extension name='"+dependency+"'/>")+"</extension></extensioninfo>");
        return root;
    }
    private HybrisContext context() throws Exception {
        Files.createDirectories(home.resolve("bin/platform"));Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/localextensions.xml"),"<hybrisconfig><extensions><extension name='backoffice'/></extensions></hybrisconfig>");
        var context=new HybrisContext(home);context.initialize();return context;
    }
    private Path source(Path root,String relative,String code) throws Exception {
        Path file=root.resolve(relative);Files.createDirectories(file.getParent());Files.writeString(file,code);return file;
    }
    private void javac(Path source,Path output) throws Exception {
        Files.createDirectories(output);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",output.toString(),source.toString()));
    }
    @Test void backofficeModuleRootsOnlyForBackofficeModules() throws Exception {
        Path eligible=extension("example",true,null),ordinary=extension("ordinary",false,null);
        context();var platform=new HybrisPlatformContext(home,AgentConfig.parse(null));platform.initialize();
        assertTrue(platform.getSourceDirs().get("example").contains(eligible.resolve("backoffice/src")));
        assertTrue(platform.getClassOutputDirs().get("example").contains(eligible.resolve("backoffice/classes")));
        assertFalse(platform.getSourceDirs().get("ordinary").contains(ordinary.resolve("backoffice/src")));
        assertFalse(platform.getClassOutputDirs().containsKey("ordinary"));
        Path file=eligible.resolve("backoffice/classes/com/example/Widget.class");
        assertEquals("com.example.Widget",platform.resolveClassName(file));
        assertEquals(eligible.resolve("backoffice/classes"),platform.resolveOutputDir(file));
        var filtered=new HybrisPlatformContext(home,AgentConfig.parse("watchExtensions=ordinary"));filtered.initialize();
        assertFalse(filtered.getSourceDirs().containsKey("example"));
        assertFalse(filtered.getClassOutputDirs().containsKey("example"));
    }
    @Test void autoCompileRoutesBackofficeSourceToBackofficeClasses() throws Exception {
        Path ext=extension("example",true,null),framework=extension("backoffice",false,null);
        javac(source(home,"framework/DefaultWidgetController.java","package com.hybris.cockpitng.util; public class DefaultWidgetController {}"),
                framework.resolve("web/webroot/WEB-INF/classes"));
        var context=context();var compiler=new IncrementalCompiler(new ClasspathResolver(context).resolve(),context);
        Path core=source(ext,"src/com/example/Core.java","package com.example; public class Core {public int value(){return 1;}}");
        Path widget=source(ext,"backoffice/src/com/example/Widget.java","package com.example; public class Widget extends com.hybris.cockpitng.util.DefaultWidgetController {public int value(){return new Core().value();}}");
        var result=compiler.compileBatch(List.of(widget,core),"example");
        assertTrue(result.isSuccess(),result.getErrors().toString());
        assertTrue(Files.isRegularFile(ext.resolve("backoffice/classes/com/example/Widget.class")));
        assertTrue(Files.isRegularFile(ext.resolve("classes/com/example/Core.class")));
        assertFalse(Files.exists(ext.resolve("classes/com/example/Widget.class")));
        assertFalse(Files.exists(ext.resolve("backoffice/classes/com/example/Core.class")));
        Files.writeString(core,"package com.example; public class Core {public int value(){return 2;}}");
        Files.setLastModifiedTime(core,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()+5000));
        result=compiler.compile(widget,"example");
        assertTrue(result.isSuccess(),result.getErrors().toString());
        assertTrue(result.getCompiledClasses().containsKey("com.example.Core"),"newer implicit core source is compiled");
        assertArrayEquals(result.getCompiledClasses().get("com.example.Core"),Files.readAllBytes(ext.resolve("classes/com/example/Core.class")));
        assertFalse(Files.exists(ext.resolve("backoffice/classes/com/example/Core.class")));
    }
    @Test void backofficeModuleCompilesAgainstRequiredModuleBofOnlyType() throws Exception { dependency(false); }
    @Test void backofficeModuleCompilesAgainstWatchedRequiredModuleOutput() throws Exception { dependency(true); }
    private void dependency(boolean watched) throws Exception {
        Path a=extension("example",true,"middle"),b=extension("dependency",true,null);
        extension("middle",false,"dependency"); // transitive required input
        if (!watched) {
            Path installed=home.resolve("bin/modules/example/dependency");
            Files.createDirectories(installed.getParent());Files.move(b,installed);b=installed;
            // Native required modules need not be repeated in localextensions.xml.
        }
        Path output=b.resolve("backoffice/classes");
        javac(source(home,"fixture/Required.java","package com.example; public class Required {}"),output);
        Path jar=b.resolve("resources/backoffice/dependency_bof.jar");Files.createDirectories(jar.getParent());
        try(var stream=new JarOutputStream(Files.newOutputStream(jar))) {
            stream.putNextEntry(new JarEntry("com/example/Required.class"));
            stream.write(Files.readAllBytes(output.resolve("com/example/Required.class")));stream.closeEntry();
        }
        if(!watched) Files.delete(output.resolve("com/example/Required.class"));
        var context=context();var compiler=new IncrementalCompiler(new ClasspathResolver(context).resolve(),context,
                watched?Set.of("example","dependency"):Set.of("example"));
        Path file=source(a,"backoffice/src/com/example/UsesRequired.java","package com.example; public class UsesRequired extends Required {}");
        var result=compiler.compile(file,"example");
        assertTrue(result.isSuccess(),result.getErrors().toString());
        assertTrue(Files.isRegularFile(a.resolve("backoffice/classes/com/example/UsesRequired.class")));
        assertFalse(Files.exists(a.resolve("classes/com/example/UsesRequired.class")));
    }
    @Test void queuedRequiredModuleOutputIsVisibleBeforeAtomicPublication() throws Exception {
        Path a=extension("example",true,"dependency"),b=extension("dependency",true,null);
        var context=context();var compiler=new IncrementalCompiler(new ClasspathResolver(context).resolve(),context);
        Path dependency=source(b,"backoffice/src/com/example/Required.java","package com.example; public class Required {}");
        assertTrue(compiler.compile(dependency,"dependency").isSuccess());
        Files.writeString(dependency,"package com.example; public class Required { public int added(){return 7;} }");
        Path caller=source(a,"backoffice/src/com/example/UsesRequired.java","package com.example; public class UsesRequired { public int run(){return new Required().added();} }");
        Map<String,List<Path>> queued=new LinkedHashMap<>();queued.put("example",List.of(caller));queued.put("dependency",List.of(dependency));
        var result=compiler.compilePackage(queued);
        assertTrue(result.isSuccess(),result.getErrors().toString());
        assertTrue(Files.isRegularFile(a.resolve("backoffice/classes/com/example/UsesRequired.class")));
        assertTrue(result.getCompiledClasses().containsKey("com.example.Required"));
    }
    @Test void failedBackofficeGroupDoesNotPublishSuccessfulCoreGroup() throws Exception {
        Path ext=extension("example",true,null);var context=context();
        var compiler=new IncrementalCompiler(new ClasspathResolver(context).resolve(),context);
        Path core=source(ext,"src/com/example/Good.java","package com.example; public class Good {}");
        Path bad=source(ext,"backoffice/src/com/example/Bad.java","package com.example; public class Bad { broken }");
        assertFalse(compiler.compileBatch(List.of(core,bad),"example").isSuccess());
        assertFalse(Files.exists(ext.resolve("classes/com/example/Good.class")));
    }
}
