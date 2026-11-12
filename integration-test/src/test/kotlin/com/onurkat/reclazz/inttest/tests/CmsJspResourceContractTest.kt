package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.http.HttpResult

fun main() {
    val body="""<!doctype html><html><span id="reclazz-cms-view-proof">jsp-v2 &amp; &lt;owned&gt;</span></html>"""
    fun verify(text:String,mime:String="text/html",status:Int=200)=requireCmsJspHtml(HttpResult(status,text),mime,"jsp-v2")
    verify(body);requireCmsJspHtml(HttpResult(200,body.replace("jsp-v2","jsp-v1")),"text/html","jsp-v1")
    for(text in listOf(body.replace("jsp-v2","jsp-v1"),body.replace("&amp;","&"),body.replace("&lt;owned&gt;","<owned>"),body.replace("reclazz-cms-view-proof","foreign"),body+body))check(runCatching {verify(text)}.isFailure)
    check(runCatching {verify(body,"application/json")}.isFailure)
    check(runCatching {verify(body,status=500)}.isFailure)
    check(runCatching {verify(body.removePrefix("<!doctype html>"))}.isFailure)
    println("CMS_JSP_RESOURCE_CONTRACT 10 checks PASS")
}
