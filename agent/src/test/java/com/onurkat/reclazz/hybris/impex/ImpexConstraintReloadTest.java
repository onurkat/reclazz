package com.onurkat.reclazz.hybris.impex;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImpexConstraintReloadTest {
    @TempDir Path root;
    final class Fixture implements AutoCloseable {
        final ClassLoader caller=Thread.currentThread().getContextClassLoader();
        final List<Object> contexts=ApplicationContextHolder.getAllContexts();
        final List<String> messages=new ArrayList<>();
        final StatusReporter.StatusListener listener=(level,text)->messages.add(level+": "+text);
        final URLClassLoader loader; final Class<?> state; Path file;
        Fixture(String mode,boolean nullCaller)throws Exception {
            Map<String,String> sources=new LinkedHashMap<>();
            sources.put("de/hybris/platform/core/Registry.java","package de.hybris.platform.core; public class Registry {public static boolean hasCurrentTenant(){com.example.Context.check();return true;}}");
            sources.put("de/hybris/platform/servicelayer/impex/ImpExResource.java","package de.hybris.platform.servicelayer.impex; public interface ImpExResource {}");
            sources.put("de/hybris/platform/servicelayer/impex/impl/StreamBasedImpExResource.java","package de.hybris.platform.servicelayer.impex.impl; public class StreamBasedImpExResource implements de.hybris.platform.servicelayer.impex.ImpExResource {public StreamBasedImpExResource(java.io.InputStream s,String encoding){com.example.Context.check();}}");
            sources.put("de/hybris/platform/validation/services/ValidationService.java","package de.hybris.platform.validation.services; public interface ValidationService {void reloadValidationEngine();}");
            sources.put("de/hybris/platform/servicelayer/search/FlexibleSearchQuery.java","""
                package de.hybris.platform.servicelayer.search;
                public class FlexibleSearchQuery {
                    public final String query;public boolean disabled;public java.util.List<?> classes;
                    public FlexibleSearchQuery(String q){com.example.Context.check();query=q;}
                    public void setDisableSearchRestrictions(boolean b){disabled=b;}
                    public void setResultClassList(java.util.List<?> c){classes=c;}
                }
                """);
            sources.put("de/hybris/platform/servicelayer/search/FlexibleSearchService.java","package de.hybris.platform.servicelayer.search; public interface FlexibleSearchService {SearchResult search(FlexibleSearchQuery q);}");
            sources.put("de/hybris/platform/servicelayer/search/SearchResult.java","package de.hybris.platform.servicelayer.search; public interface SearchResult {java.util.List<?> getResult();}");
            sources.put("com/example/Context.java","""
                package com.example;
                import java.util.*;
                import de.hybris.platform.servicelayer.search.*;
                public class Context {
                    public static String mode;public static int imports,reloads,queries;public static boolean finishedSeen;
                    final Search search=new Search();final Validation validation=new Validation();
                    public static void check(){if(Thread.currentThread().getContextClassLoader()!=Context.class.getClassLoader())throw new AssertionError("wrong native TCCL");}
                    public boolean containsBean(String name){return name.equals("importService");}
                    public Object getBean(String name){if(!name.equals("importService"))check();return switch(name){
                        case "importService" -> new Importer();
                        case "flexibleSearchService" -> mode.equals("missing-search")?null:mode.equals("changed-search")&&imports>0?new Search():search;
                        case "validationService" -> mode.equals("missing-validation")?null:mode.equals("changed-validation")&&imports>0?new Validation():validation;
                        default -> throw new IllegalArgumentException();};}
                    public static class Importer {
                        public Result importData(de.hybris.platform.servicelayer.impex.ImpExResource r){check();if(mode.equals("throw-import"))throw new IllegalStateException("import rejected");imports++;if(mode.equals("mutate"))Thread.currentThread().setContextClassLoader(new ClassLoader(null){});return new Result();}
                    }
                    public static class Result {
                        public boolean isError(){return mode.equals("refused");}
                        public boolean isFinished(){check();finishedSeen=true;if(mode.equals("finished-failure"))throw new IllegalStateException("private-native-data");return !mode.equals("unfinished");}
                        public Object getUnresolvedLines(){return List.of("private-native-data");}
                    }
                    public static class Validation implements de.hybris.platform.validation.services.ValidationService {
                        public void reloadValidationEngine(){check();if(!finishedSeen||imports==0)throw new AssertionError("reload before finished import");reloads++;if(mode.equals("reload-failure"))throw new IllegalStateException("private-native-data");if(mode.equals("reload-linkage"))throw new NoClassDefFoundError("private-native-data");}
                    }
                    public static class Search implements FlexibleSearchService {
                        public SearchResult search(FlexibleSearchQuery q){check();queries++;
                            if(!q.disabled||!q.classes.equals(List.of(Long.class,Date.class)))throw new AssertionError("query options");
                            boolean group=q.query.equals("SELECT COUNT({pk}),MAX({modifiedtime}) FROM {ConstraintGroup}");
                            if(!group&&!q.query.equals("SELECT COUNT({pk}),MAX({modifiedtime}) FROM {AbstractConstraint}"))throw new AssertionError("not a fixed root query");
                            if(mode.equals("before-query")||mode.equals("after-query")&&imports>0)throw new IllegalStateException("private-native-data");
                            if(mode.equals("empty"))return ()->List.of(Arrays.asList(0L,null));
                            Object count=2L;Object time=new Date(100L);
                            if(mode.equals("wrong-count"))count=2.0;
                            if(mode.equals("negative-count"))count=-1L;
                            if(mode.equals("wrong-max"))time="private-native-data";
                            if(mode.equals("null-max"))time=null;
                            if(mode.equals("zero-with-max"))count=0L;
                            boolean change=group?mode.equals("group")||mode.equals("both"):!Set.of("unchanged","group").contains(mode);
                            if(change&&imports>0)time=new Date(100L+imports*100L);
                            if(mode.equals("count")) {time=new Date(100L);count=2L+imports;}
                            Object c=count,t=time;return ()->List.of(Arrays.asList(c,t));
                        }
                    }
                }
                """);
            List<String> args=new ArrayList<>(List.of("-proc:none","-d",root.toString()));
            for(var e:sources.entrySet()){Path p=root.resolve(e.getKey());Files.createDirectories(p.getParent());Files.writeString(p,e.getValue());args.add(p.toString());}
            assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
            loader=new URLClassLoader(new java.net.URL[]{root.toUri().toURL()},null);state=loader.loadClass("com.example.Context");mode(mode);
            file=Files.writeString(root.resolve("owned.impex"),"UPDATE Title;code[unique=true]\n;owned\n");
            ApplicationContextHolder.clear();ApplicationContextHolder.register(state.getConstructor().newInstance());StatusReporter.addListener(listener);RestartLedger.clear();
            Thread.currentThread().setContextClassLoader(nullCaller?null:new ClassLoader(null){});
        }
        void mode(String value)throws Exception{state.getField("mode").set(null,value);}
        int value(String name)throws Exception{return state.getField(name).getInt(null);}
        boolean message(String value){return messages.stream().anyMatch(s->s.contains(value));}
        void invoke()throws Exception{ClassLoader original=Thread.currentThread().getContextClassLoader();new ImpexAutoImporter().importFile(file,null);assertSame(original,Thread.currentThread().getContextClassLoader());assertFalse(message("private-native-data"),messages.toString());}
        void ok(){assertTrue(message("OK: ImpEx imported: owned.impex"),messages.toString());}
        @Override public void close()throws Exception{Thread.currentThread().setContextClassLoader(caller);StatusReporter.removeListener(listener);ApplicationContextHolder.clear();for(Object c:contexts)ApplicationContextHolder.register(c);RestartLedger.clear();loader.close();}
    }
    void changed(String mode)throws Exception{try(var f=new Fixture(mode,false)){f.invoke();f.ok();assertEquals(1,f.value("reloads"));assertEquals(4,f.value("queries"));assertEquals(1,f.messages.stream().filter(s->s.contains("Validation engine reloaded after ImpEx changed constraints: owned.impex")).count());}}
    @Test void changedConstraintRootReloadsExactlyOnceAfterFinishedSuccessfulImport()throws Exception{changed("constraint");}
    @Test void changedGroupRootReloadsExactlyOnce()throws Exception{changed("group");}
    @Test void bothRootsChangedStillReloadsOnce()throws Exception{changed("both");}
    @Test void countChangeReloadsOnce()throws Exception{changed("count");}
    @Test void unchangedRootsDoNotReload()throws Exception{try(var f=new Fixture("unchanged",false)){f.invoke();f.ok();assertEquals(0,f.value("reloads"));assertTrue(f.messages.stream().noneMatch(s->s.contains("Validation engine")));}}
    @Test void emptyRootsHaveNullMaxAndDoNotReload()throws Exception{try(var f=new Fixture("empty",false)){f.invoke();f.ok();assertEquals(0,f.value("reloads"));assertFalse(f.message("skipped"));}}
    void unavailable(String mode)throws Exception{try(var f=new Fixture(mode,false)){f.invoke();f.ok();assertEquals(0,f.value("reloads"));assertTrue(f.message("refresh skipped"),f.messages.toString());assertEquals(0,RestartLedger.size());}}
    @Test void missingSearchService()throws Exception{unavailable("missing-search");}
    @Test void missingValidationService()throws Exception{unavailable("missing-validation");}
    @Test void queryFailureBefore()throws Exception{unavailable("before-query");}
    @Test void queryFailureAfter()throws Exception{unavailable("after-query");}
    @Test void changedSearchIdentity()throws Exception{unavailable("changed-search");}
    @Test void changedValidationIdentity()throws Exception{unavailable("changed-validation");}
    @Test void nonintegralCount()throws Exception{unavailable("wrong-count");}
    @Test void negativeCount()throws Exception{unavailable("negative-count");}
    @Test void wrongMaxType()throws Exception{unavailable("wrong-max");}
    @Test void nullMaxForNonemptyRoot()throws Exception{unavailable("null-max");}
    @Test void nonnullMaxForZeroRoot()throws Exception{unavailable("zero-with-max");}
    @Test void finishedInspectionFailurePreservesImportOK()throws Exception{unavailable("finished-failure");}
    @Test void refusedImportDoesNotReload()throws Exception{try(var f=new Fixture("refused",false)){f.invoke();assertEquals(0,f.value("reloads"));assertTrue(f.message("ImpEx import had errors"));assertFalse(f.message("OK: ImpEx imported"));}}
    @Test void thrownImportDoesNotReload()throws Exception{try(var f=new Fixture("throw-import",false)){f.invoke();assertEquals(0,f.value("reloads"));assertFalse(f.message("OK: ImpEx imported"));}}
    @Test void unfinishedResultDoesNotReload()throws Exception{try(var f=new Fixture("unfinished",false)){f.invoke();f.ok();assertEquals(0,f.value("reloads"));}}
    @Test void nullCallerRestored()throws Exception{try(var f=new Fixture("constraint",true)){f.invoke();f.ok();assertEquals(1,f.value("reloads"));}}
    @Test void importMutatingTcclDoesNotAffectNativeRefreshOrCaller()throws Exception{changed("mutate");}
    @Test void reloadFailurePreservesOKAndLaterSuccessfulFileResolvesOnlyOwnConcern()throws Exception{try(var f=new Fixture("reload-failure",false)){RestartLedger.note("other","other reason");RestartLedger.note("SAP validation engine","other reason");f.invoke();f.ok();assertEquals(3,RestartLedger.size());assertTrue(f.message("could not reload"));f.mode("constraint");f.file=root.resolve("later.impex");Files.writeString(f.file,"UPDATE Title;code[unique=true]\n;other\n");f.invoke();assertEquals(2,RestartLedger.size());assertEquals(2,f.value("reloads"));assertTrue(String.join("\n",RestartLedger.digest()).contains("other reason"));}}
    @Test void unchangedImportDoesNotResolveFailure()throws Exception{try(var f=new Fixture("reload-failure",false)){f.invoke();f.mode("unchanged");f.invoke();assertEquals(1,RestartLedger.size());assertEquals(1,f.value("reloads"));}}
    @Test void reloadLinkageFailurePreservesOKAndRecordsConcern()throws Exception{try(var f=new Fixture("reload-linkage",false)){f.invoke();f.ok();assertEquals(1,RestartLedger.size());assertTrue(f.message("could not reload"));}}
}
