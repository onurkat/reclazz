package com.onurkat.reclazztest.controllers;

import de.hybris.platform.catalog.CatalogVersionService;
import de.hybris.platform.catalog.enums.ArticleApprovalStatus;
import de.hybris.platform.catalog.model.CatalogModel;
import de.hybris.platform.catalog.model.CatalogVersionModel;
import de.hybris.platform.commercefacades.product.ProductFacade;
import de.hybris.platform.commercefacades.product.data.ProductData;
import de.hybris.platform.commercefacades.product.impl.DefaultProductFacade;
import de.hybris.platform.converters.impl.AbstractPopulatingConverter;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.session.SessionExecutionBody;
import de.hybris.platform.servicelayer.session.SessionService;
import de.hybris.platform.servicelayer.user.UserService;
import de.hybris.platform.tx.Transaction;
import jakarta.annotation.Resource;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** Native facade responses and UUID-owned persistence; no response-value construction. */
@RestController
@RequestMapping("/test/batch/facade")
public class FacadeChainProbeController {
    @Autowired private ModelService models;
    @Autowired private FlexibleSearchService search;
    @Autowired private SessionService sessions;
    @Autowired private UserService userService;
    @Autowired private CatalogVersionService catalogs;
    @Autowired private CommonI18NService commonI18NService;
    @Resource(name="reclazzProbeProductFacade") private ProductFacade target;
    @Resource(name="reclazzProbeControlProductFacade") private ProductFacade control;

    private void owned(String run) {
        if(!run.matches("^reclazz-facade-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
            throw new IllegalArgumentException("Invalid owned run");
    }
    private List<String> codes(String run) {owned(run);return List.of(run+"-target-one",run+"-target-two",run+"-control");}
    private <T> List<T> rows(String type,String field,Object ids) {
        return search.<T>search("SELECT {pk} FROM {"+type+"} WHERE {"+field+"} IN (?ids)",Map.of("ids",ids)).getResult();
    }
    private List<CatalogVersionModel> versions(String run) {
        owned(run);return search.<CatalogVersionModel>search("SELECT {v.pk} FROM {CatalogVersion AS v JOIN Catalog AS c ON {v.catalog}={c.pk}} WHERE {c.id}=?id",Map.of("id",run)).getResult();
    }
    private Map<String,Object> sessionState() {
        return Map.of("user",userService.getCurrentUser().getPk().toString(),"catalogs",new HashSet<>(catalogs.getSessionCatalogVersions()),"language",commonI18NService.getCurrentLanguage().getPk().toString());
    }
    private <T> T local(String run,boolean select,Supplier<T> body) {
        owned(run);var before=sessionState();
        try {
            return sessions.executeInLocalView(new SessionExecutionBody() {
                @Override public Object execute() {
                    commonI18NService.setCurrentLanguage(commonI18NService.getLanguage("en"));
                    if(select)catalogs.setSessionCatalogVersions(List.of(catalogs.getCatalogVersion(run,"selected")));
                    return body.get();
                }
            },userService.getAdminUser());
        } finally {if(!before.equals(sessionState()))throw new IllegalStateException("Outer user/catalog/language session changed");}
    }
    private Map<String,Integer> counts(String run) {
        return Map.of("products",rows("Product","code",codes(run)).size(),"versions",versions(run).size(),"catalogs",rows("Catalog","id",List.of(run)).size());
    }
    @GetMapping("/count") public Map<String,Object> count(@RequestParam("run") String run) {
        var result=new LinkedHashMap<String,Object>(local(run,false,()->counts(run)));
        result.put("run",run);result.put("ownedRemaining",local(run,false,()->counts(run)).values().stream().mapToInt(Integer::intValue).sum());result.put("sessionRestored",true);return result;
    }
    @PostMapping("/seed") public synchronized Map<String,Object> seed(@RequestParam("run") String run) {
        local(run,false,()->{
            if(counts(run).values().stream().anyMatch(n->n!=0))throw new IllegalStateException("Owned nonce already present");
            Transaction tx=Transaction.current();if(tx.isRunning())throw new IllegalStateException("Seed requires own transaction");tx.begin();
            try {
                CatalogModel catalog=models.create(CatalogModel.class);catalog.setId(run);models.save(catalog);
                CatalogVersionModel cv=models.create(CatalogVersionModel.class);cv.setCatalog(catalog);cv.setVersion("selected");models.save(cv);catalogs.setSessionCatalogVersions(List.of(cv));
                var names=List.of("facade-v1","facade-v2","control-stable");int i=0;
                for(String code:codes(run)) {
                    ProductModel p=models.create(ProductModel.class);p.setCode(code);p.setCatalogVersion(cv);p.setName(names.get(i++),Locale.ENGLISH);
                    p.setManufacturerName("stable-manufacturer");p.setApprovalStatus(ArticleApprovalStatus.APPROVED);models.save(p);
                }
                tx.commit();return true;
            } finally {try {if(tx.isRunning())tx.rollback();} finally {models.detachAll();}}
        });return facts(run);
    }
    // Read the actual native facade fields through its verified protected accessors.
    private Object collaborator(ProductFacade facade,String getter) {
        try {var m=DefaultProductFacade.class.getDeclaredMethod(getter);m.setAccessible(true);return m.invoke(facade);}
        catch(ReflectiveOperationException e) {throw new IllegalStateException("Native facade collaborator unavailable",e);}
    }
    private Map<String,Object> chain(ProductFacade facade) {
        var converter=(AbstractPopulatingConverter<?,?>)collaborator(facade,"getProductConverter");
        return Map.of("facade",facade.getClass().getName(),"service",collaborator(facade,"getProductService").getClass().getName(),
            "converter",converter.getClass().getName(),"populators",converter.getPopulators().stream().map(p->p.getClass().getName()).toList());
    }
    @GetMapping("/facts") public Map<String,Object> facts(@RequestParam("run") String run) {
        var result=local(run,true,()->{
            var products=new TreeMap<String,Object>();
            for(ProductModel p:this.<ProductModel>rows("Product","code",codes(run))) {
                if(!run.equals(p.getCatalogVersion().getCatalog().getId()))throw new IllegalStateException("Foreign product catalog");
                products.put(p.getCode().substring(run.length()+1),Map.of("pk",p.getPk().toString(),"name",p.getName(Locale.ENGLISH)));
            }
            var proof=new LinkedHashMap<String,Object>();proof.put("run",run);proof.put("products",products);
            proof.put("catalogPk",catalogs.getCatalogVersion(run,"selected").getCatalog().getPk().toString());proof.put("versionPk",catalogs.getCatalogVersion(run,"selected").getPk().toString());
            proof.put("target",chain(target));proof.put("control",chain(control));proof.put("jvmStart",ManagementFactory.getRuntimeMXBean().getStartTime());return proof;
        });result.put("sessionRestored",true);return result;
    }
    @GetMapping("/target") public ProductData target(@RequestParam("run") String run) {
        return local(run,true,()->target.getProductForCodeAndOptions(run+"-target",null));
    }
    @GetMapping("/control") public ProductData control(@RequestParam("run") String run) {
        return local(run,true,()->control.getProductForCodeAndOptions(run+"-control",null));
    }
    @PostMapping("/cleanup") public synchronized Map<String,Object> cleanup(@RequestParam("run") String run) {
        local(run,false,()->{
            var cvs=versions(run);catalogs.setSessionCatalogVersions(cvs);
            var products=this.<ProductModel>rows("Product","code",codes(run));
            for(var p:products)if(!cvs.contains(p.getCatalogVersion()))throw new IllegalStateException("Foreign owned product code");
            models.removeAll(products);models.removeAll(cvs);models.removeAll(this.<CatalogModel>rows("Catalog","id",List.of(run)));return true;
        });return count(run);
    }
}
