package com.onurkat.reclazz.inttest.tests

fun main() {
    val run="reclazz-restriction-00000000-0000-0000-0000-000000000001"
    val seedText="""{"run":"$run","sessionRestored":true,"users":{"a":"101","b":"102"},"catalogs":{"selected":"201","other":"202"},"restrictions":{"a":"301","b":"302"},"products":{"selected-a-one":"401","selected-a-two":"402","selected-b-one":"403","selected-b-two":"404","other-a-one":"405","other-a-two":"406","other-b-one":"407","other-b-two":"408"},"adminAll":["401","402","403","404","405","406","407","408"],"control":["501"]}"""
    val seed=batchJson.readTree(seedText);requireRestrictionSeed(seed,run)
    val response="""{"run":"$run","actor":"a","version":"selected","userPk":"101","catalogPk":"201","restrictionPks":["301"],"enabled":true,"sessionRestored":true,"products":["401"],"control":["501"]}"""
    fun verify(text:String)=requireRestrictionResult(batchJson.readTree(text),seed,run,"a","selected","one")
    verify(response)
    val invalid=listOf(response.replace("[\"401\"]","[]"),response.replace("[\"401\"]","[\"999\"]"),
        response.replace("[\"401\"]","[\"403\"]"),response.replace("[\"401\"]","[\"405\"]"),response.replace("[\"401\"]","[\"402\"]"),
        response.replace("userPk\":\"101","userPk\":\"102"),response.replace("catalogPk\":\"201","catalogPk\":\"202"),
        response.replace("enabled\":true","enabled\":false"),response.replace("sessionRestored\":true","sessionRestored\":false"),
        response.replace("[\"501\"]","[\"502\"]"),response.replace("[\"301\"]","[\"302\"]"),response.replace("[\"401\"]","[\"401\",\"401\"]"))
    for(text in invalid)check(runCatching {verify(text)}.isFailure)
    check(runCatching {requireRestrictionSeed(batchJson.readTree(seedText.replace("adminAll\":[\"401\"","adminAll\":[\"999\"")),run)}.isFailure)
    requireDefaultMembership(committedDefaultMembership.toList());check(committedDefaultMembership.size==60)
    println("SEARCH_RESTRICTION_CONTRACT ${invalid.size+4} checks PASS")
}
