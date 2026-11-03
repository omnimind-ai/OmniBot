package cn.com.omnimind.assists.controller.http

import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Prevent OkHttp's HTTP 503/421 follow-ups, which can replay a POST even with
 * retryOnConnectionFailure disabled. The Agent transport alone decides whether
 * to create another call; the body remains writable for that explicit attempt.
 */
internal fun RequestBody.asSingleAttemptInferenceBody(): RequestBody {
    val delegate = this
    return object : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun isDuplex() = delegate.isDuplex()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
    }
}
