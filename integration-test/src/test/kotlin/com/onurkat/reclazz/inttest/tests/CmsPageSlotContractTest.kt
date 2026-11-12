package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.http.HttpResult

fun main() {
    val nonce="reclazz-cms-00000000-0000-0000-0000-000000000001"
    val body="""<!doctype html><div id="reclazz-cms-page-proof" data-nonce="$nonce" data-marker="page-v2" data-uid="$nonce-target" data-pk="123" data-version="selected" data-page="$nonce-page" data-page-pk="456" data-slot="$nonce-target-slot" data-slot-pk="789" data-position="target"><span class="content">selected-page-target</span></div>"""
    fun verify(text:String,mime:String="text/html")=requireCmsPageHtml(HttpResult(200,text),mime,nonce,"selected","target","page-v2",CmsPageProof("123","456","789"))
    check(verify(body)==CmsPageProof("123","456","789"))
    val invalid=listOf(body.replace("page-v2","page-v1"),body.replace("data-version=\"selected\"","data-version=\"other\""),
        body.replace("data-pk=\"123\"","data-pk=\"\""),body.replace("selected-page-target","selected-page-control"),
        body.replace(nonce,"foreign"),body+body,body.replace("data-page-pk=\"456\"","data-page-pk=\"457\""),
        body.replace("data-slot-pk=\"789\"","data-slot-pk=\"790\""),body.replace("data-position=\"target\"","data-position=\"control\""),
        body.replace("$nonce-target-slot","$nonce-control-slot"))
    for(text in invalid)check(runCatching {verify(text)}.isFailure)
    check(runCatching {verify(body,"application/json")}.isFailure)
    println("CMS_PAGE_SLOT_CONTRACT 12 checks PASS")
}
