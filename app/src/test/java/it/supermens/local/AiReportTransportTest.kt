// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class AiReportTransportTest {
    private val endpoint="https://script.google.com/macros/s/TEST_DEPLOYMENT/exec"
    private val content="https://script.googleusercontent.com/macros/echo?user_content_key=synthetic&lib=test"
    private val body="{\"response\":\"synthetic\"}".toByteArray()
    private class Reply(url:URL, val status:Int=200, val location:String?=null,
                        val reply:String="{\"version\":1,\"ok\":true}", val mime:String="application/json; charset=utf-8",
                        val failure:IOException?=null):HttpsURLConnection(url) {
        val sent=ByteArrayOutputStream()
        var closed=false
        override fun getResponseCode():Int {failure?.let {throw it}; return status}
        override fun getContentType()=mime
        override fun getContentLengthLong()=-1L
        override fun getHeaderField(name:String)=if(name=="Location") location else null
        override fun getOutputStream()=sent
        override fun getInputStream()=ByteArrayInputStream(reply.toByteArray())
        override fun disconnect() {closed=true}
        override fun usingProxy()=false
        override fun connect() {}
        override fun getCipherSuite()="TLS_TEST"
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
    }

    @Test fun onlyProductionAppsScriptEndpointsAreAccepted() {
        assertTrue(AiReportTransport.endpointAllowed(endpoint))
        listOf("http://script.google.com/macros/s/X/exec", "$endpoint?x=1", "$endpoint#fragment",
            "https://script.google.com:443/macros/s/X/exec", "https://user@script.google.com/macros/s/X/exec",
            "https://script.google.com.evil.test/macros/s/X/exec", "https://script.google.com/macros/s/X/dev",
            "https://script.googleusercontent.com/macros/echo", "https://example.com/reports", "broken",
            "https://script.google.com/macros/s/%2e%2e/exec").forEach {assertFalse(it,AiReportTransport.endpointAllowed(it))}
    }
    @Test fun contentServiceRedirectFetchesJsonWithGetAndNeverResendsTheReportBody() {
        for(code in listOf(302,303)) {
            val connections=mutableListOf<Reply>()
            val result=AiReportTransport.submit(endpoint,body) {url ->
                Reply(url,if(connections.isEmpty()) code else 200,if(connections.isEmpty()) content else null)
                    .also {connections+=it}
            }
            assertTrue(result.contains("\"ok\":true"));assertEquals(2,connections.size)
            assertEquals(endpoint,connections[0].url.toString());assertEquals("POST",connections[0].requestMethod)
            assertArrayEquals(body,connections[0].sent.toByteArray())
            assertEquals(content,connections[1].url.toString());assertEquals("GET",connections[1].requestMethod)
            assertEquals(0,connections[1].sent.size())
            assertTrue(connections.all {it.closed && !it.instanceFollowRedirects && !it.useCaches})
        }
    }
    @Test fun untrustedRedirectsAreRejectedBeforeOpeningAnotherConnection() {
        listOf("https://evil.test/macros/echo", "https://script.googleusercontent.com.evil.test/macros/echo",
            "http://script.googleusercontent.com/macros/echo", "https://user@script.googleusercontent.com/macros/echo",
            "https://script.googleusercontent.com:443/macros/echo", "$content#fragment",
            "https://script.googleusercontent.com/other", "https://accounts.google.com/login", endpoint).forEach {target ->
            var calls=0
            assertThrows(Exception::class.java) {AiReportTransport.submit(endpoint,body) {url -> calls++;Reply(url,302,target)}}
            assertEquals(target,1,calls)
        }
    }
    @Test fun postPreservingAndPermanentRedirectsAreNotReplayed() {
        for(code in listOf(301,307,308)) {
            var calls=0
            assertThrows(IOException::class.java) {AiReportTransport.submit(endpoint,body) {url -> calls++;Reply(url,code,content)}}
            assertEquals(1,calls)
        }
    }
    @Test fun redirectLoopsMissingLocationsHtmlErrorsAndOversizedRepliesCannotConfirmSending() {
        var calls=0
        assertThrows(IOException::class.java) {AiReportTransport.submit(endpoint,body) {url -> calls++;Reply(url,302,content)}}
        assertEquals(4,calls)
        listOf(Reply(URL(endpoint),302), Reply(URL(endpoint),201), Reply(URL(endpoint),503),
            Reply(URL(endpoint),mime="text/html",reply="login"), Reply(URL(endpoint),reply=""),
            Reply(URL(endpoint),reply="x".repeat(4097))).forEach {connection ->
            assertThrows(IOException::class.java) {AiReportTransport.submit(endpoint,body) {connection}}
            assertTrue(connection.closed)
        }
    }
    @Test fun noNetworkAndTimeoutPropagateWithoutAutomaticRetries() {
        for(error in listOf(IOException("offline"),SocketTimeoutException("timeout"))) {
            var calls=0
            val connection=Reply(URL(endpoint),failure=error)
            val caught=assertThrows(IOException::class.java) {AiReportTransport.submit(endpoint,body) {calls++;connection}}
            assertSame(error,caught);assertEquals(1,calls);assertTrue(connection.closed)
        }
    }
    @Test fun requestLimitIsCheckedBeforeOpeningAnyConnection() {
        for(bytes in listOf(byteArrayOf(),ByteArray(32769))) {
            assertThrows(IllegalArgumentException::class.java) {AiReportTransport.submit(endpoint,bytes) {error("Must not connect")}}
        }
    }
}
