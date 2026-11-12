package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.ProbeSearchRestrictionDao;
import de.hybris.platform.catalog.CatalogVersionService;
import de.hybris.platform.catalog.model.CatalogModel;
import de.hybris.platform.catalog.model.CatalogVersionModel;
import de.hybris.platform.core.Registry;
import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.core.model.type.SearchRestrictionModel;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.search.restriction.SearchRestrictionService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.session.SessionExecutionBody;
import de.hybris.platform.servicelayer.session.SessionService;
import de.hybris.platform.servicelayer.type.TypeService;
import de.hybris.platform.servicelayer.user.UserService;
import de.hybris.platform.tx.Transaction;
import de.hybris.platform.util.Config;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** Exact UUID-owned fixtures only; proof queries never disable native restrictions. */
@RestController
@RequestMapping("/test/batch/restriction")
public class SearchRestrictionProbeController {
    @Autowired private ModelService models;
    @Autowired private FlexibleSearchService search;
    @Autowired private SessionService sessions;
    @Autowired private UserService userService;
    @Autowired private CatalogVersionService catalogs;
    @Autowired private SearchRestrictionService restrictions;
    @Autowired private TypeService types;

    private void owned(String run) {
        if(!run.matches("^reclazz-restriction-[0-9a-f-]{36}$"))throw new IllegalArgumentException("Invalid owned run");
    }
    private List<String> ids(String run) { owned(run);return List.of(run+"-a",run+"-b"); }
    private List<String> codes(String run) {owned(run);return List.of(run+"-a-one",run+"-a-two",run+"-b-one",run+"-b-two");}
    private <T> List<T> rows(String type,String field,Object ids) {
        return search.<T>search("SELECT {pk} FROM {"+type+"} WHERE {"+field+"} IN (?ids)",Map.of("ids",ids)).getResult();
    }
    private List<CatalogVersionModel> versions(String run) {
        owned(run);return search.<CatalogVersionModel>search("SELECT {v.pk} FROM {CatalogVersion AS v JOIN Catalog AS c ON {v.catalog}={c.pk}} WHERE {c.id}=?id",Map.of("id",run)).getResult();
    }
    private List<String> pks(Collection<? extends ItemModel> values) {return values.stream().map(x->x.getPk().toString()).sorted().toList();}
    private Map<String,Object> sessionState() {
        return Map.of("user",userService.getCurrentUser().getPk().toString(),"catalogs",new HashSet<>(pks(catalogs.getSessionCatalogVersions())),
                "enabled",restrictions.isSearchRestrictionsEnabled(),"restrictions",new HashSet<>(restrictions.getSessionSearchRestrictions()));
    }
    private <T> T local(UserModel user,Supplier<T> body) {
        var before=sessionState();
        try {
            return sessions.executeInLocalView(new SessionExecutionBody() {
                @Override public Object execute() {
                    restrictions.enableSearchRestrictions();return body.get();
                }
            },user);
        } finally {
            if(!before.equals(sessionState()))throw new IllegalStateException("Outer restriction/user/catalog session changed");
        }
    }
    private Map<String,Integer> counts(String run) {
        return Map.of("users",rows("User","uid",ids(run)).size(),"restrictions",rows("SearchRestriction","code",ids(run)).size(),
                "products",rows("Product","code",codes(run)).size(),"versions",versions(run).size(),
                "catalogs",rows("Catalog","id",List.of(run)).size(),"titles",rows("Title","code",List.of(run+"-control")).size());
    }
    @GetMapping("/gates")
    public Map<String,Object> gates() {
        return Map.of("restrictionsConfigured",Config.getBoolean("restrictions.enabled",false),
                "nativeTypes",types.getComposedTypeForCode("SearchRestriction")!=null&&types.getComposedTypeForCode("Product")!=null,
                "nativeAdmin",userService.isAdmin(userService.getAdminUser()));
    }
    @GetMapping("/count")
    public Map<String,Object> count(@RequestParam("run") String run) {
        owned(run);var result=new LinkedHashMap<String,Object>(local(userService.getAdminUser(),()->counts(run)));
        result.put("run",run);result.put("ownedRemaining",local(userService.getAdminUser(),()->counts(run)).values().stream().mapToInt(Integer::intValue).sum());
        result.put("sessionRestored",true);return result;
    }
    @PostMapping("/seed")
    public synchronized Map<String,Object> seed(@RequestParam("run") String run) {
        owned(run);
        local(userService.getAdminUser(),()->{
            if(counts(run).values().stream().anyMatch(n->n!=0))throw new IllegalStateException("Owned nonce already present");
            Transaction tx=Transaction.current();if(tx.isRunning())throw new IllegalStateException("Seed requires own transaction");tx.begin();
            try {
                var ownedUsers=new ArrayList<UserModel>();
                for(String id:ids(run)) {UserModel user=models.create(UserModel.class);user.setUid(id);models.save(user);if(userService.isAdmin(user))throw new IllegalStateException("Owned admin forbidden");ownedUsers.add(user);}
                CatalogModel catalog=models.create(CatalogModel.class);catalog.setId(run);models.save(catalog);
                for(String version:List.of("selected","other")) {
                    CatalogVersionModel cv=models.create(CatalogVersionModel.class);cv.setCatalog(catalog);cv.setVersion(version);
                    cv.setReadPrincipals(new ArrayList<>(ownedUsers));models.save(cv);catalogs.setSessionCatalogVersions(List.of(cv));
                    for(String code:codes(run)) {ProductModel product=models.create(ProductModel.class);product.setCode(code);product.setCatalogVersion(cv);models.save(product);}
                }
                ItemModel title=models.create("Title");models.setAttributeValue(title,"code",run+"-control");models.save(title);
                for(UserModel user:ownedUsers) {
                    SearchRestrictionModel restriction=models.create(SearchRestrictionModel.class);restriction.setCode(user.getUid());restriction.setPrincipal(user);
                    restriction.setRestrictedType(types.getComposedTypeForCode("Product"));restriction.setActive(true);restriction.setGenerate(false);
                    restriction.setQuery("{item:code} LIKE '"+user.getUid()+"-%' AND {item:catalogVersion} IN (?session.catalogversions)");models.save(restriction);
                }
                tx.commit();return true;
            } finally {try {if(tx.isRunning())tx.rollback();} finally {models.detachAll();}}
        });return facts(run);
    }
    @GetMapping("/facts")
    public Map<String,Object> facts(@RequestParam("run") String run) {
        owned(run);var result=local(userService.getAdminUser(),()->{
            var products=new TreeMap<String,String>();
            for(ProductModel product:this.<ProductModel>rows("Product","code",codes(run))) {
                if(!run.equals(product.getCatalogVersion().getCatalog().getId()))throw new IllegalStateException("Foreign product catalog");
                products.put(product.getCatalogVersion().getVersion()+"-"+product.getCode().substring(run.length()+1),product.getPk().toString());
            }
            var userIds=new TreeMap<String,String>();for(UserModel user:this.<UserModel>rows("User","uid",ids(run)))userIds.put(user.getUid().substring(run.length()+1),user.getPk().toString());
            var cvIds=new TreeMap<String,String>();for(var cv:versions(run))cvIds.put(cv.getVersion(),cv.getPk().toString());
            var restrictionIds=new TreeMap<String,String>();for(SearchRestrictionModel restriction:this.<SearchRestrictionModel>rows("SearchRestriction","code",ids(run)))restrictionIds.put(restriction.getCode().substring(run.length()+1),restriction.getPk().toString());
            var proof=new LinkedHashMap<String,Object>();proof.put("run",run);proof.put("products",products);proof.put("users",userIds);proof.put("catalogs",cvIds);proof.put("restrictions",restrictionIds);
            proof.put("adminAll",pks(this.<ProductModel>rows("Product","code",codes(run))));proof.put("control",pks(this.<ItemModel>rows("Title","code",List.of(run+"-control"))));return proof;
        });result.put("sessionRestored",true);return result;
    }
    @GetMapping("/find")
    public Map<String,Object> find(@RequestParam("run") String run,@RequestParam("actor") String actor,@RequestParam("version") String version) {
        owned(run);if(!List.of("a","b").contains(actor)||!List.of("selected","other").contains(version))throw new IllegalArgumentException("Foreign selection");
        var user=userService.getUser(run+"-"+actor);if(userService.isAdmin(user))throw new IllegalStateException("Admin proof forbidden");
        var result=local(user,()->{
            var cv=catalogs.getCatalogVersion(run,version);catalogs.setSessionCatalogVersions(List.of(cv));
            if(!catalogs.canRead(cv,user)||!userService.getCurrentUser().equals(user))throw new IllegalStateException("Native user/catalog mismatch");
            var active=restrictions.getActiveSearchRestrictions(user,true,List.of(types.getComposedTypeForCode("Product")));
            if(active.stream().noneMatch(x->(run+"-"+actor).equals(x.getCode())))throw new IllegalStateException("Owned native restriction missing");
            var dao=Registry.getApplicationContext().getBean("reclazzProbeSearchRestrictionDao",ProbeSearchRestrictionDao.class);
            var proof=new LinkedHashMap<String,Object>();proof.put("run",run);proof.put("actor",actor);proof.put("version",version);proof.put("userPk",userService.getCurrentUser().getPk().toString());proof.put("catalogPk",cv.getPk().toString());
            proof.put("restrictionPks",pks(active));proof.put("enabled",restrictions.isSearchRestrictionsEnabled());proof.put("products",pks(dao.find(run)));
            proof.put("control",pks(this.<ItemModel>rows("Title","code",List.of(run+"-control"))));return proof;
        });result.put("sessionRestored",true);return result;
    }
    @PostMapping("/cleanup")
    public synchronized Map<String,Object> cleanup(@RequestParam("run") String run) {
        owned(run);local(userService.getAdminUser(),()->{
            var found=this.<SearchRestrictionModel>rows("SearchRestriction","code",ids(run));
            for(var restriction:found)if(!ids(run).contains(restriction.getPrincipal().getUid())||!"Product".equals(restriction.getRestrictedType().getCode()))throw new IllegalStateException("Foreign restriction");
            models.removeAll(found);
            var cvs=versions(run);catalogs.setSessionCatalogVersions(cvs);
            var products=this.<ProductModel>rows("Product","code",codes(run));
            for(var product:products)if(!cvs.contains(product.getCatalogVersion()))throw new IllegalStateException("Foreign owned code");
            models.removeAll(products);models.removeAll(cvs);models.removeAll(this.<CatalogModel>rows("Catalog","id",List.of(run)));
            models.removeAll(this.<ItemModel>rows("Title","code",List.of(run+"-control")));models.removeAll(this.<UserModel>rows("User","uid",ids(run)));return true;
        });return count(run);
    }
}
