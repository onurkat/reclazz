package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.http.HttpResult

fun main() {
    val nonce="reclazz-cms-00000000-0000-0000-0000-000000000001"
    val body="""<!doctype html><div id="reclazz-cms-uid-proof" data-nonce="$nonce" data-marker="uid-v2" data-uid="$nonce-target" data-pk="123" data-version="selected"><span class="content">selected-target</span></div>"""
    fun verify(text:String,mime:String="text/html")=requireCmsUidHtml(HttpResult(200,text),mime,nonce,"selected","target","uid-v2")
    check(verify(body)=="123")
    val invalid=listOf(body.replace("uid-v2","uid-v1"),body.replace("data-version=\"selected\"","data-version=\"other\""),
        body.replace("data-pk=\"123\"","data-pk=\"\""),body.replace("selected-target","other-target"),
        body.replace(nonce,"foreign"),body+body)
    for(text in invalid)check(runCatching {verify(text)}.isFailure)
    check(runCatching {verify(body,"application/json")}.isFailure)
    println("CMS_UID_CONTRACT 8 checks PASS")
}
