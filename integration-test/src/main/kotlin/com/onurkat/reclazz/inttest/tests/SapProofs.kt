package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.http.HttpResult

/** One nonce, one actual model save, one validation. A reload event alone proves none of these. */
internal fun interceptorSaveFailure(response: HttpResult, nonce: String): String? {
    val expected = "validated-v2:reclazz-probe-$nonce|calls=1"
    return if (response.statusCode == 200 && response.body == expected) null
    else "Expected HTTP 200 and '$expected', got ${response.statusCode}: ${response.body}"
}
