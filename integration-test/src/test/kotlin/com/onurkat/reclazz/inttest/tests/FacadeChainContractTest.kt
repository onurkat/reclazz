package com.onurkat.reclazz.inttest.tests

fun main() {
    var checks=0
    val run="reclazz-facade-00000000-0000-0000-0000-000000000001"
    val data="""{"name":"facade-v1","manufacturer":"stable-manufacturer","purchasable":true,"configurable":false,"averageRating":null}"""
    val first=batchJson.readTree(data)
    requireFacadeData(first,"facade-v1");checks++
    requireFacadeData(batchJson.readTree(data.replace("facade-v1","facade-v2")),"facade-v2",first);checks++
    val invalid=listOf(data.replace("facade-v1","facade-v2"),data.replace("stable-manufacturer","other"),data.replace("purchasable\":true","purchasable\":false"),data.replace("configurable\":false","configurable\":true"),data.replace("averageRating\":null","averageRating\":3.0"))
    for(text in invalid) {check(runCatching {requireFacadeData(batchJson.readTree(text),"facade-v1",first)}.isFailure);checks++}
    val chain=""""facade":"de.hybris.platform.commercefacades.product.impl.DefaultProductFacade","converter":"de.hybris.platform.converters.impl.AbstractPopulatingConverter","populators":["de.hybris.platform.commercefacades.product.converters.populator.ProductBasicPopulator"]"""
    val facts="""{"run":"$run","sessionRestored":true,"catalogPk":"101","versionPk":"102","jvmStart":1000,"products":{"target-one":{"pk":"201","name":"facade-v1"},"target-two":{"pk":"202","name":"facade-v2"},"control":{"pk":"203","name":"control-stable"}},"target":{$chain,"service":"com.onurkat.reclazztest.batch.ProbeFacadeProductService"},"control":{$chain,"service":"de.hybris.platform.product.impl.DefaultProductService"}}"""
    requireFacadeFacts(batchJson.readTree(facts),run);checks++
    for(text in listOf(facts.replace("impl.DefaultProductFacade","impl.FakeFacade"),facts.replace("impl.AbstractPopulatingConverter","impl.FakeConverter"),facts.replace("populator.ProductBasicPopulator","populator.FakePopulator"),facts.replace("impl.DefaultProductService","impl.FakeService"),facts.replace("sessionRestored\":true","sessionRestored\":false"),facts.replace("jvmStart\":1000","jvmStart\":0"),facts.replace("pk\":\"202","pk\":\"201"),facts.replace("control-stable","wrong"))) {
        check(runCatching {requireFacadeFacts(batchJson.readTree(text),run)}.isFailure);checks++
    }
    // The live phase additionally compares the entire facts object with its seed.
    val seed=batchJson.readTree(facts)
    for(text in listOf(facts.replace("pk\":\"202","pk\":\"999"),facts.replace("jvmStart\":1000","jvmStart\":2000"))) {check(batchJson.readTree(text)!=seed);checks++}
    val cleanup="""{"run":"$run","sessionRestored":true,"products":0,"versions":0,"catalogs":0,"ownedRemaining":0}"""
    requireFacadeCleanup(batchJson.readTree(cleanup),run);checks++
    for(key in listOf("products","versions","catalogs","ownedRemaining")) {check(runCatching {requireFacadeCleanup(batchJson.readTree(cleanup.replace("\"$key\":0","\"$key\":1")),run)}.isFailure);checks++}
    requireDefaultMembership(committedDefaultMembership.toList());check(committedDefaultMembership.size==62&&"FacadeChainLiveTest" in committedDefaultMembership);checks++
    println("FACADE_CHAIN_CONTRACT $checks checks PASS")
}
