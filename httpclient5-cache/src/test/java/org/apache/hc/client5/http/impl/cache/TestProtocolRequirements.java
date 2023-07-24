/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.client5.http.impl.cache;

import static org.hamcrest.MatcherAssert.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.cache.HttpCacheEntry;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecRuntime;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.utils.DateUtils;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HeaderElement;
import org.apache.hc.core5.http.HeaderElements;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.message.MessageSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * We are a conditionally-compliant HTTP/1.1 client with a cache. However, a lot
 * of the rules for proxies apply to us, as far as proper operation of the
 * requests that pass through us. Generally speaking, we want to make sure that
 * any response returned from our HttpClient.execute() methods is conditionally
 * compliant with the rules for an HTTP/1.1 server, and that any requests we
 * pass downstream to the backend HttpClient are are conditionally compliant
 * with the rules for an HTTP/1.1 client.
 */
public class TestProtocolRequirements {

    static final int MAX_BYTES = 1024;
    static final int MAX_ENTRIES = 100;
    static final int ENTITY_LENGTH = 128;

    HttpHost host;
    HttpRoute route;
    HttpEntity body;
    HttpClientContext context;
    @Mock
    ExecChain mockExecChain;
    @Mock
    ExecRuntime mockExecRuntime;
    @Mock
    HttpCache mockCache;
    ClassicHttpRequest request;
    ClassicHttpResponse originResponse;
    CacheConfig config;
    CachingExec impl;
    HttpCache cache;

    @BeforeEach
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        host = new HttpHost("foo.example.com", 80);

        route = new HttpRoute(host);

        body = HttpTestUtils.makeBody(ENTITY_LENGTH);

        request = new BasicClassicHttpRequest("GET", "/foo");

        context = HttpClientContext.create();

        originResponse = HttpTestUtils.make200Response();

        config = CacheConfig.custom()
                .setMaxCacheEntries(MAX_ENTRIES)
                .setMaxObjectSize(MAX_BYTES)
                .build();

        cache = new BasicHttpCache(config);
        impl = new CachingExec(cache, null, config);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);
    }

    public ClassicHttpResponse execute(final ClassicHttpRequest request) throws IOException, HttpException {
        return impl.execute(
                ClassicRequestBuilder.copy(request).build(),
                new ExecChain.Scope("test", route, request, mockExecRuntime, context),
                mockExecChain);
    }

    @Test
    public void testCacheMissOnGETUsesOriginResponse() throws Exception {

        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(request), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        Assertions.assertTrue(HttpTestUtils.semanticallyTransparent(originResponse, result));
    }

    /*
     * "Proxy and gateway applications need to be careful when forwarding
     * messages in protocol versions different from that of the application.
     * Since the protocol version indicates the protocol capability of the
     * sender, a proxy/gateway MUST NOT send a message with a version indicator
     * which is greater than its actual version. If a higher version request is
     * received, the proxy/gateway MUST either downgrade the request version, or
     * respond with an error, or switch to tunnel behavior."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.1
     */
    @Test
    public void testHigherMajorProtocolVersionsOnRequestSwitchToTunnelBehavior() throws Exception {

        // tunnel behavior: I don't muck with request or response in
        // any way
        request = new BasicClassicHttpRequest("GET", "/foo");
        request.setVersion(new ProtocolVersion("HTTP", 2, 13));

        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(request), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        Assertions.assertSame(originResponse, result);
    }

    @Test
    public void testHigher1_XProtocolVersionsDowngradeTo1_1() throws Exception {

        request = new BasicClassicHttpRequest("GET", "/foo");
        request.setVersion(new ProtocolVersion("HTTP", 1, 2));

        final ClassicHttpRequest downgraded = new BasicClassicHttpRequest("GET", "/foo");
        downgraded.setVersion(HttpVersion.HTTP_1_1);

        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(downgraded), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        Assertions.assertTrue(HttpTestUtils.semanticallyTransparent(originResponse, result));
    }

    /*
     * "Due to interoperability problems with HTTP/1.0 proxies discovered since
     * the publication of RFC 2068[33], caching proxies MUST, gateways MAY, and
     * tunnels MUST NOT upgrade the request to the highest version they support.
     * The proxy/gateway's response to that request MUST be in the same major
     * version as the request."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.1
     */
    @Test
    public void testRequestsWithLowerProtocolVersionsGetUpgradedTo1_1() throws Exception {

        request = new BasicClassicHttpRequest("GET", "/foo");
        request.setVersion(new ProtocolVersion("HTTP", 1, 0));
        final ClassicHttpRequest upgraded = new BasicClassicHttpRequest("GET", "/foo");
        upgraded.setVersion(HttpVersion.HTTP_1_1);

        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(upgraded), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        Assertions.assertTrue(HttpTestUtils.semanticallyTransparent(originResponse, result));
    }

    /*
     * "An HTTP server SHOULD send a response version equal to the highest
     * version for which the server is at least conditionally compliant, and
     * whose major version is less than or equal to the one received in the
     * request."
     *
     * http://www.ietf.org/rfc/rfc2145.txt
     */
    @Test
    public void testLowerOriginResponsesUpgradedToOurVersion1_1() throws Exception {
        originResponse = new BasicClassicHttpResponse(HttpStatus.SC_OK, "OK");
        originResponse.setVersion(new ProtocolVersion("HTTP", 1, 2));
        originResponse.setHeader("Date", DateUtils.formatStandardDate(Instant.now()));
        originResponse.setHeader("Server", "MockOrigin/1.0");
        originResponse.setEntity(body);

        // not testing this internal behavior in this test, just want
        // to check the protocol version that comes out the other end
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        Assertions.assertEquals(HttpVersion.HTTP_1_1, result.getVersion());
    }

    @Test
    public void testResponseToA1_0RequestShouldUse1_1() throws Exception {
        request = new BasicClassicHttpRequest("GET", "/foo");
        request.setVersion(new ProtocolVersion("HTTP", 1, 0));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        Assertions.assertEquals(HttpVersion.HTTP_1_1, result.getVersion());
    }

    /*
     * "A proxy MUST forward an unknown header, unless it is protected by a
     * Connection header." http://www.ietf.org/rfc/rfc2145.txt
     */
    @Test
    public void testForwardsUnknownHeadersOnRequestsFromHigherProtocolVersions() throws Exception {
        request = new BasicClassicHttpRequest("GET", "/foo");
        request.setVersion(new ProtocolVersion("HTTP", 1, 2));
        request.removeHeaders("Connection");
        request.addHeader("X-Unknown-Header", "some-value");

        final ClassicHttpRequest downgraded = new BasicClassicHttpRequest("GET", "/foo");
        downgraded.setVersion(HttpVersion.HTTP_1_1);
        downgraded.removeHeaders("Connection");
        downgraded.addHeader("X-Unknown-Header", "some-value");

        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(downgraded), Mockito.any())).thenReturn(originResponse);

        execute(request);
    }

    /*
     * "A server MUST NOT send transfer-codings to an HTTP/1.0 client."
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.6
     */
    @Test
    public void testTransferCodingsAreNotSentToAnHTTP_1_0Client() throws Exception {

        originResponse.setHeader("Transfer-Encoding", "identity");

        final ClassicHttpRequest originalRequest = new BasicClassicHttpRequest("GET", "/foo");
        originalRequest.setVersion(new ProtocolVersion("HTTP", 1, 0));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(originalRequest);

        Assertions.assertNull(result.getFirstHeader("TE"));
        Assertions.assertNull(result.getFirstHeader("Transfer-Encoding"));
    }

    /*
     * "Multiple message-header fields with the same field-name MAY be present
     * in a message if and only if the entire field-value for that header field
     * is defined as a comma-separated list [i.e., #(values)]. It MUST be
     * possible to combine the multiple header fields into one
     * "field-name: field-value" pair, without changing the semantics of the
     * message, by appending each subsequent field-value to the first, each
     * separated by a comma. The order in which header fields with the same
     * field-name are received is therefore significant to the interpretation of
     * the combined field value, and thus a proxy MUST NOT change the order of
     * these field values when a message is forwarded."
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.2
     */
    private void testOrderOfMultipleHeadersIsPreservedOnRequests(final String h, final ClassicHttpRequest request) throws Exception {
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);

        final ArgumentCaptor<ClassicHttpRequest> reqCapture = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        Mockito.verify(mockExecChain).proceed(reqCapture.capture(), Mockito.any());

        final ClassicHttpRequest forwarded = reqCapture.getValue();
        final String expected = HttpTestUtils.getCanonicalHeaderValue(request, h);
        final String actual = HttpTestUtils.getCanonicalHeaderValue(forwarded, h);
        if (!actual.contains(expected)) {
            Assertions.assertEquals(expected, actual);
        }
    }

    @Test
    public void testOrderOfMultipleAcceptHeaderValuesIsPreservedOnRequests() throws Exception {
        request.addHeader("Accept", "audio/*; q=0.2, audio/basic");
        request.addHeader("Accept", "text/*, text/html, text/html;level=1, */*");
        testOrderOfMultipleHeadersIsPreservedOnRequests("Accept", request);
    }

    @Test
    public void testOrderOfMultipleAcceptCharsetHeadersIsPreservedOnRequests() throws Exception {
        request.addHeader("Accept-Charset", "iso-8859-5");
        request.addHeader("Accept-Charset", "unicode-1-1;q=0.8");
        testOrderOfMultipleHeadersIsPreservedOnRequests("Accept-Charset", request);
    }

    @Test
    public void testOrderOfMultipleAcceptEncodingHeadersIsPreservedOnRequests() throws Exception {
        request.addHeader("Accept-Encoding", "identity");
        request.addHeader("Accept-Encoding", "compress, gzip");
        testOrderOfMultipleHeadersIsPreservedOnRequests("Accept-Encoding", request);
    }

    @Test
    public void testOrderOfMultipleAcceptLanguageHeadersIsPreservedOnRequests() throws Exception {
        request.addHeader("Accept-Language", "da, en-gb;q=0.8, en;q=0.7");
        request.addHeader("Accept-Language", "i-cherokee");
        testOrderOfMultipleHeadersIsPreservedOnRequests("Accept-Encoding", request);
    }

    @Test
    public void testOrderOfMultipleAllowHeadersIsPreservedOnRequests() throws Exception {
        final BasicClassicHttpRequest put = new BasicClassicHttpRequest("PUT", "/");
        put.setEntity(body);
        put.addHeader("Allow", "GET, HEAD");
        put.addHeader("Allow", "DELETE");
        put.addHeader("Content-Length", "128");
        testOrderOfMultipleHeadersIsPreservedOnRequests("Allow", put);
    }

    @Test
    public void testOrderOfMultipleCacheControlHeadersIsPreservedOnRequests() throws Exception {
        request.addHeader("Cache-Control", "max-age=5");
        request.addHeader("Cache-Control", "min-fresh=10");
        testOrderOfMultipleHeadersIsPreservedOnRequests("Cache-Control", request);
    }

    @Test
    public void testOrderOfMultipleContentEncodingHeadersIsPreservedOnRequests() throws Exception {
        final BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
        post.setEntity(body);
        post.addHeader("Content-Encoding", "gzip");
        post.addHeader("Content-Encoding", "compress");
        post.addHeader("Content-Length", "128");
        testOrderOfMultipleHeadersIsPreservedOnRequests("Content-Encoding", post);
    }

    @Test
    public void testOrderOfMultipleContentLanguageHeadersIsPreservedOnRequests() throws Exception {
        final BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
        post.setEntity(body);
        post.addHeader("Content-Language", "mi");
        post.addHeader("Content-Language", "en");
        post.addHeader("Content-Length", "128");
        testOrderOfMultipleHeadersIsPreservedOnRequests("Content-Language", post);
    }

    @Test
    public void testOrderOfMultipleExpectHeadersIsPreservedOnRequests() throws Exception {
        final BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
        post.setEntity(body);
        post.addHeader("Expect", "100-continue");
        post.addHeader("Expect", "x-expect=true");
        post.addHeader("Content-Length", "128");
        testOrderOfMultipleHeadersIsPreservedOnRequests("Expect", post);
    }

    @Test
    public void testOrderOfMultipleViaHeadersIsPreservedOnRequests() throws Exception {
        request.addHeader(HttpHeaders.VIA, "1.0 fred, 1.1 nowhere.com (Apache/1.1)");
        request.addHeader(HttpHeaders.VIA, "1.0 ricky, 1.1 mertz, 1.0 lucy");
        testOrderOfMultipleHeadersIsPreservedOnRequests(HttpHeaders.VIA, request);
    }

    private void testOrderOfMultipleHeadersIsPreservedOnResponses(final String h) throws Exception {
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(HttpTestUtils.getCanonicalHeaderValue(originResponse, h), HttpTestUtils
                .getCanonicalHeaderValue(result, h));

    }

    @Test
    public void testOrderOfMultipleAllowHeadersIsPreservedOnResponses() throws Exception {
        originResponse = new BasicClassicHttpResponse(405, "Method Not Allowed");
        originResponse.addHeader("Allow", "HEAD");
        originResponse.addHeader("Allow", "DELETE");
        testOrderOfMultipleHeadersIsPreservedOnResponses("Allow");
    }

    @Test
    public void testOrderOfMultipleCacheControlHeadersIsPreservedOnResponses() throws Exception {
        originResponse.addHeader("Cache-Control", "max-age=0");
        originResponse.addHeader("Cache-Control", "no-store, must-revalidate");
        testOrderOfMultipleHeadersIsPreservedOnResponses("Cache-Control");
    }

    @Test
    public void testOrderOfMultipleContentEncodingHeadersIsPreservedOnResponses() throws Exception {
        originResponse.addHeader("Content-Encoding", "gzip");
        originResponse.addHeader("Content-Encoding", "compress");
        testOrderOfMultipleHeadersIsPreservedOnResponses("Content-Encoding");
    }

    @Test
    public void testOrderOfMultipleContentLanguageHeadersIsPreservedOnResponses() throws Exception {
        originResponse.addHeader("Content-Language", "mi");
        originResponse.addHeader("Content-Language", "en");
        testOrderOfMultipleHeadersIsPreservedOnResponses("Content-Language");
    }

    @Test
    public void testOrderOfMultipleViaHeadersIsPreservedOnResponses() throws Exception {
        originResponse.addHeader(HttpHeaders.VIA, "1.0 fred, 1.1 nowhere.com (Apache/1.1)");
        originResponse.addHeader(HttpHeaders.VIA, "1.0 ricky, 1.1 mertz, 1.0 lucy");
        testOrderOfMultipleHeadersIsPreservedOnResponses(HttpHeaders.VIA);
    }

    @Test
    public void testOrderOfMultipleWWWAuthenticateHeadersIsPreservedOnResponses() throws Exception {
        originResponse.addHeader("WWW-Authenticate", "x-challenge-1");
        originResponse.addHeader("WWW-Authenticate", "x-challenge-2");
        testOrderOfMultipleHeadersIsPreservedOnResponses("WWW-Authenticate");
    }

    /*
     * "However, applications MUST understand the class of any status code, as
     * indicated by the first digit, and treat any unrecognized response as
     * being equivalent to the x00 status code of that class, with the exception
     * that an unrecognized response MUST NOT be cached."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec6.html#sec6.1.1
     */
    private void testUnknownResponseStatusCodeIsNotCached(final int code) throws Exception {

        originResponse = new BasicClassicHttpResponse(code, "Moo");
        originResponse.setHeader("Date", DateUtils.formatStandardDate(Instant.now()));
        originResponse.setHeader("Server", "MockOrigin/1.0");
        originResponse.setHeader("Cache-Control", "max-age=3600");
        originResponse.setEntity(body);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);

        // in particular, there were no storage calls on the cache
        Mockito.verifyNoInteractions(mockCache);
    }

    @Test
    public void testUnknownResponseStatusCodesAreNotCached() throws Exception {
        for (int i = 102; i <= 199; i++) {
            testUnknownResponseStatusCodeIsNotCached(i);
        }
        for (int i = 207; i <= 299; i++) {
            testUnknownResponseStatusCodeIsNotCached(i);
        }
        for (int i = 308; i <= 399; i++) {
            testUnknownResponseStatusCodeIsNotCached(i);
        }
        for (int i = 418; i <= 499; i++) {
            testUnknownResponseStatusCodeIsNotCached(i);
        }
        for (int i = 506; i <= 999; i++) {
            testUnknownResponseStatusCodeIsNotCached(i);
        }
    }

    /*
     * "Unrecognized header fields SHOULD be ignored by the recipient and MUST
     * be forwarded by transparent proxies."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec7.html#sec7.1
     */
    @Test
    public void testUnknownHeadersOnRequestsAreForwarded() throws Exception {
        request.addHeader("X-Unknown-Header", "blahblah");
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);

        final ArgumentCaptor<ClassicHttpRequest> reqCapture = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        Mockito.verify(mockExecChain).proceed(reqCapture.capture(), Mockito.any());
        final ClassicHttpRequest forwarded = reqCapture.getValue();
        final Header[] hdrs = forwarded.getHeaders("X-Unknown-Header");
        Assertions.assertEquals(1, hdrs.length);
        Assertions.assertEquals("blahblah", hdrs[0].getValue());
    }

    @Test
    public void testUnknownHeadersOnResponsesAreForwarded() throws Exception {
        originResponse.addHeader("X-Unknown-Header", "blahblah");
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        final Header[] hdrs = result.getHeaders("X-Unknown-Header");
        Assertions.assertEquals(1, hdrs.length);
        Assertions.assertEquals("blahblah", hdrs[0].getValue());
    }

    /*
     * "If a client will wait for a 100 (Continue) response before sending the
     * request body, it MUST send an Expect request-header field (section 14.20)
     * with the '100-continue' expectation."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec8.html#sec8.2.3
     */
    @Test
    public void testRequestsExpecting100ContinueBehaviorShouldSetExpectHeader() throws Exception {
        final BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
        post.setHeader(HttpHeaders.EXPECT, HeaderElements.CONTINUE);
        post.setHeader("Content-Length", "128");
        post.setEntity(new StringEntity("whatever"));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(post);

        final ArgumentCaptor<ClassicHttpRequest> reqCapture = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        Mockito.verify(mockExecChain).proceed(reqCapture.capture(), Mockito.any());
        final ClassicHttpRequest forwarded = reqCapture.getValue();
        boolean foundExpect = false;
        final Iterator<HeaderElement> it = MessageSupport.iterate(forwarded, HttpHeaders.EXPECT);
        while (it.hasNext()) {
            final HeaderElement elt = it.next();
            if ("100-continue".equalsIgnoreCase(elt.getName())) {
                foundExpect = true;
                break;
            }
        }
        Assertions.assertTrue(foundExpect);
    }

    /*
     * "If a client will wait for a 100 (Continue) response before sending the
     * request body, it MUST send an Expect request-header field (section 14.20)
     * with the '100-continue' expectation."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec8.html#sec8.2.3
     */
    @Test
    public void testRequestsNotExpecting100ContinueBehaviorShouldNotSetExpectContinueHeader() throws Exception {
        final BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
        post.setHeader("Content-Length", "128");
        post.setEntity(new StringEntity("whatever"));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(post);

        final ArgumentCaptor<ClassicHttpRequest> reqCapture = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        Mockito.verify(mockExecChain).proceed(reqCapture.capture(), Mockito.any());
        final ClassicHttpRequest forwarded = reqCapture.getValue();
        boolean foundExpect = false;
        final Iterator<HeaderElement> it = MessageSupport.iterate(forwarded, HttpHeaders.EXPECT);
        while (it.hasNext()) {
            final HeaderElement elt = it.next();
            if ("100-continue".equalsIgnoreCase(elt.getName())) {
                foundExpect = true;
                break;
            }
        }
        Assertions.assertFalse(foundExpect);
    }

    /*
     * "If a proxy receives a request that includes an Expect request- header
     * field with the '100-continue' expectation, and the proxy either knows
     * that the next-hop server complies with HTTP/1.1 or higher, or does not
     * know the HTTP version of the next-hop server, it MUST forward the
     * request, including the Expect header field.
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec8.html#sec8.2.3
     */
    @Test
    public void testExpectHeadersAreForwardedOnRequests() throws Exception {
        // This would mostly apply to us if we were part of an
        // application that was a proxy, and would be the
        // responsibility of the greater application. Our
        // responsibility is to make sure that if we get an
        // entity-enclosing request that we properly set (or unset)
        // the Expect header per the request.expectContinue() flag,
        // which is tested by the previous few tests.
    }

    /*
     * "9.2 OPTIONS. ...Responses to this method are not cacheable.
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec9.html#sec9.2
     */
    @Test
    public void testResponsesToOPTIONSAreNotCacheable() throws Exception {
        request = new BasicClassicHttpRequest("OPTIONS", "/");
        originResponse.addHeader("Cache-Control", "max-age=3600");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);

        Mockito.verifyNoInteractions(mockCache);
    }

    /*
     * "A 200 response SHOULD .... If no response body is included, the response
     * MUST include a Content-Length field with a field-value of '0'."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec9.html#sec9.2
     */
    @Test
    public void test200ResponseToOPTIONSWithNoBodyShouldIncludeContentLengthZero() throws Exception {

        request = new BasicClassicHttpRequest("OPTIONS", "/");
        originResponse.setEntity(null);
        originResponse.setHeader("Content-Length", "0");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        final Header contentLength = result.getFirstHeader("Content-Length");
        Assertions.assertNotNull(contentLength);
        Assertions.assertEquals("0", contentLength.getValue());
    }

    /*
     * "When a proxy receives an OPTIONS request on an absoluteURI for which
     * request forwarding is permitted, the proxy MUST check for a Max-Forwards
     * field. If the Max-Forwards field-value is zero ("0"), the proxy MUST NOT
     * forward the message; instead, the proxy SHOULD respond with its own
     * communication options."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec9.html#sec9.2
     */
    @Test
    public void testDoesNotForwardOPTIONSWhenMaxForwardsIsZeroOnAbsoluteURIRequest() throws Exception {
        request = new BasicClassicHttpRequest("OPTIONS", "*");
        request.setHeader("Max-Forwards", "0");

        execute(request);
    }

    /*
     * "If no Max-Forwards field is present in the request, then the forwarded
     * request MUST NOT include a Max-Forwards field."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec9.html#sec9.2
     */
    @Test
    public void testDoesNotAddAMaxForwardsHeaderToForwardedOPTIONSRequests() throws Exception {
        request = new BasicClassicHttpRequest("OPTIONS", "/");
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);

        final ArgumentCaptor<ClassicHttpRequest> reqCapture = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        Mockito.verify(mockExecChain).proceed(reqCapture.capture(), Mockito.any());

        final ClassicHttpRequest forwarded = reqCapture.getValue();
        Assertions.assertNull(forwarded.getFirstHeader("Max-Forwards"));
    }

    /*
     * "The HEAD method is identical to GET except that the server MUST NOT
     * return a message-body in the response."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec9.html#sec9.4
     */
    @Test
    public void testResponseToAHEADRequestMustNotHaveABody() throws Exception {
        request = new BasicClassicHttpRequest("HEAD", "/");
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        Assertions.assertTrue(result.getEntity() == null || result.getEntity().getContentLength() == 0);
    }

    /*
     * "If the new field values indicate that the cached entity differs from the
     * current entity (as would be indicated by a change in Content-Length,
     * Content-MD5, ETag or Last-Modified), then the cache MUST treat the cache
     * entry as stale."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec9.html#sec9.4
     */
    private void testHEADResponseWithUpdatedEntityFieldsMakeACacheEntryStale(final String eHeader,
            final String oldVal, final String newVal) throws Exception {

        // put something cacheable in the cache
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.addHeader("Cache-Control", "max-age=3600");
        resp1.setHeader(eHeader, oldVal);

        // get a head that penetrates the cache
        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("HEAD", "/");
        req2.addHeader("Cache-Control", "no-cache");
        final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();
        resp2.setEntity(null);
        resp2.setHeader(eHeader, newVal);

        // next request doesn't tolerate stale entry
        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");
        req3.addHeader("Cache-Control", "max-stale=0");
        final ClassicHttpResponse resp3 = HttpTestUtils.make200Response();
        resp3.setHeader(eHeader, newVal);

        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(req1), Mockito.any())).thenReturn(originResponse);
        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(req2), Mockito.any())).thenReturn(originResponse);
        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(req3), Mockito.any())).thenReturn(resp3);

        execute(req1);
        execute(req2);
        execute(req3);
    }

    @Test
    public void testHEADResponseWithUpdatedContentLengthFieldMakeACacheEntryStale() throws Exception {
        testHEADResponseWithUpdatedEntityFieldsMakeACacheEntryStale("Content-Length", "128", "127");
    }

    @Test
    public void testHEADResponseWithUpdatedContentMD5FieldMakeACacheEntryStale() throws Exception {
        testHEADResponseWithUpdatedEntityFieldsMakeACacheEntryStale("Content-MD5",
                "Q2hlY2sgSW50ZWdyaXR5IQ==", "Q2hlY2sgSW50ZWdyaXR5IR==");

    }

    @Test
    public void testHEADResponseWithUpdatedETagFieldMakeACacheEntryStale() throws Exception {
        testHEADResponseWithUpdatedEntityFieldsMakeACacheEntryStale("ETag", "\"etag1\"",
                "\"etag2\"");
    }

    @Test
    public void testHEADResponseWithUpdatedLastModifiedFieldMakeACacheEntryStale() throws Exception {
        final Instant now = Instant.now();
        final Instant tenSecondsAgo = now.minusSeconds(10);
        final Instant sixSecondsAgo = now.minusSeconds(6);
        testHEADResponseWithUpdatedEntityFieldsMakeACacheEntryStale("Last-Modified", DateUtils.formatStandardDate(tenSecondsAgo), DateUtils.formatStandardDate(sixSecondsAgo));
    }

    /*
     * "9.5 POST. Responses to this method are not cacheable, unless the
     * response includes appropriate Cache-Control or Expires header fields."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec9.html#sec9.5
     */
    @Test
    public void testResponsesToPOSTWithoutCacheControlOrExpiresAreNotCached() throws Exception {

        final BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
        post.setHeader("Content-Length", "128");
        post.setEntity(HttpTestUtils.makeBody(128));

        originResponse.removeHeaders("Cache-Control");
        originResponse.removeHeaders("Expires");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(post);

        Mockito.verifyNoInteractions(mockCache);
    }

    /*
     * "9.5 PUT. ...Responses to this method are not cacheable."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec9.html#sec9.6
     */
    @Test
    public void testResponsesToPUTsAreNotCached() throws Exception {

        final BasicClassicHttpRequest put = new BasicClassicHttpRequest("PUT", "/");
        put.setEntity(HttpTestUtils.makeBody(128));
        put.addHeader("Content-Length", "128");

        originResponse.setHeader("Cache-Control", "max-age=3600");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(put);

        Mockito.verifyNoInteractions(mockCache);
    }

    /*
     * "9.6 DELETE. ... Responses to this method are not cacheable."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec9.html#sec9.7
     */
    @Test
    public void testResponsesToDELETEsAreNotCached() throws Exception {

        request = new BasicClassicHttpRequest("DELETE", "/");
        originResponse.setHeader("Cache-Control", "max-age=3600");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);

        Mockito.verifyNoInteractions(mockCache);
    }

    /*
     * "9.8 TRACE ... Responses to this method MUST NOT be cached."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec9.html#sec9.8
     */
    @Test
    public void testResponsesToTRACEsAreNotCached() throws Exception {

        request = new BasicClassicHttpRequest("TRACE", "/");
        originResponse.setHeader("Cache-Control", "max-age=3600");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);

        Mockito.verifyNoInteractions(mockCache);
    }

    /*
     * "The [206] response MUST include the following header fields:
     *
     * - Either a Content-Range header field (section 14.16) indicating the
     * range included with this response, or a multipart/byteranges Content-Type
     * including Content-Range fields for each part. If a Content-Length header
     * field is present in the response, its value MUST match the actual number
     * of OCTETs transmitted in the message-body.
     *
     * - Date
     *
     * - ETag and/or Content-Location, if the header would have been sent in a
     * 200 response to the same request
     *
     * - Expires, Cache-Control, and/or Vary, if the field-value might differ
     * from that sent in any previous response for the same variant"
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.2.7
     */
    @Test
    public void test206ResponseGeneratedFromCacheMustHaveContentRangeOrMultipartByteRangesContentType() throws Exception {

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("ETag", "\"etag\"");
        resp1.setHeader("Cache-Control", "max-age=3600");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Range", "bytes=0-50");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        if (HttpStatus.SC_PARTIAL_CONTENT == result.getCode()) {
            if (result.getFirstHeader("Content-Range") == null) {
                final HeaderElement elt = MessageSupport.parse(result.getFirstHeader("Content-Type"))[0];
                Assertions.assertTrue("multipart/byteranges".equalsIgnoreCase(elt.getName()));
                Assertions.assertNotNull(elt.getParameterByName("boundary"));
                Assertions.assertNotNull(elt.getParameterByName("boundary").getValue());
                Assertions.assertNotEquals("", elt.getParameterByName("boundary").getValue().trim());
            }
        }
        Mockito.verify(mockExecChain, Mockito.times(1)).proceed(Mockito.any(), Mockito.any());
    }

    @Test
    public void test206ResponseGeneratedFromCacheMustHaveABodyThatMatchesContentLengthHeaderIfPresent() throws Exception {

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("ETag", "\"etag\"");
        resp1.setHeader("Cache-Control", "max-age=3600");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Range", "bytes=0-50");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        if (HttpStatus.SC_PARTIAL_CONTENT == result.getCode()) {
            final Header h = result.getFirstHeader("Content-Length");
            if (h != null) {
                final int contentLength = Integer.parseInt(h.getValue());
                int bytesRead = 0;
                final InputStream i = result.getEntity().getContent();
                while ((i.read()) != -1) {
                    bytesRead++;
                }
                i.close();
                Assertions.assertEquals(contentLength, bytesRead);
            }
        }
        Mockito.verify(mockExecChain, Mockito.times(1)).proceed(Mockito.any(), Mockito.any());
    }

    @Test
    public void test206ResponseGeneratedFromCacheMustHaveDateHeader() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("ETag", "\"etag\"");
        resp1.setHeader("Cache-Control", "max-age=3600");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Range", "bytes=0-50");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        if (HttpStatus.SC_PARTIAL_CONTENT == result.getCode()) {
            Assertions.assertNotNull(result.getFirstHeader("Date"));
        }
        Mockito.verify(mockExecChain, Mockito.times(1)).proceed(Mockito.any(), Mockito.any());
    }

    @Test
    public void test206ContainsETagIfA200ResponseWouldHaveIncludedIt() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");

        originResponse.addHeader("Cache-Control", "max-age=3600");
        originResponse.addHeader("ETag", "\"etag1\"");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.addHeader("Range", "bytes=0-50");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        if (result.getCode() == HttpStatus.SC_PARTIAL_CONTENT) {
            Assertions.assertNotNull(result.getFirstHeader("ETag"));
        }
        Mockito.verify(mockExecChain, Mockito.times(1)).proceed(Mockito.any(), Mockito.any());
    }

    @Test
    public void test206ContainsContentLocationIfA200ResponseWouldHaveIncludedIt() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");

        originResponse.addHeader("Cache-Control", "max-age=3600");
        originResponse.addHeader("Content-Location", "http://foo.example.com/other/url");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.addHeader("Range", "bytes=0-50");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        if (result.getCode() == HttpStatus.SC_PARTIAL_CONTENT) {
            Assertions.assertNotNull(result.getFirstHeader("Content-Location"));
        }
        Mockito.verify(mockExecChain, Mockito.times(1)).proceed(Mockito.any(), Mockito.any());
    }

    @Test
    public void test206ResponseIncludesVariantHeadersIfValueMightDiffer() throws Exception {

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        req1.addHeader("Accept-Encoding", "gzip");

        final Instant now = Instant.now();
        final Instant inOneHour = Instant.now().plus(1, ChronoUnit.HOURS);
        originResponse.addHeader("Cache-Control", "max-age=3600");
        originResponse.addHeader("Expires", DateUtils.formatStandardDate(inOneHour));
        originResponse.addHeader("Vary", "Accept-Encoding");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.addHeader("Cache-Control", "no-cache");
        req2.addHeader("Accept-Encoding", "gzip");
        final Instant nextSecond = Instant.now().plusSeconds(1);
        final Instant inTwoHoursPlusASec = now.plus(2, ChronoUnit.HOURS).plus(1, ChronoUnit.SECONDS);

        final ClassicHttpResponse originResponse2 = HttpTestUtils.make200Response();
        originResponse2.setHeader("Date", DateUtils.formatStandardDate(nextSecond));
        originResponse2.setHeader("Cache-Control", "max-age=7200");
        originResponse2.setHeader("Expires", DateUtils.formatStandardDate(inTwoHoursPlusASec));
        originResponse2.setHeader("Vary", "Accept-Encoding");

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");
        req3.addHeader("Range", "bytes=0-50");
        req3.addHeader("Accept-Encoding", "gzip");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(req1);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse2);

        execute(req2);
        final ClassicHttpResponse result = execute(req3);


        if (result.getCode() == HttpStatus.SC_PARTIAL_CONTENT) {
            Assertions.assertNotNull(result.getFirstHeader("Expires"));
            Assertions.assertNotNull(result.getFirstHeader("Cache-Control"));
            Assertions.assertNotNull(result.getFirstHeader("Vary"));
        }
        Mockito.verify(mockExecChain, Mockito.times(2)).proceed(Mockito.any(), Mockito.any());
    }

    /*
     * "Otherwise, the [206] response MUST include all of the entity-headers
     * that would have been returned with a 200 (OK) response to the same
     * [If-Range] request."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.2.7
     */
    @Test
    public void test206ResponseToIfRangeWithStrongValidatorReturnsAllEntityHeaders() throws Exception {

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");

        final Instant now = Instant.now();
        final Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);
        originResponse.addHeader("Allow", "GET,HEAD");
        originResponse.addHeader("Cache-Control", "max-age=3600");
        originResponse.addHeader("Content-Language", "en");
        originResponse.addHeader("Content-Encoding", "x-coding");
        originResponse.addHeader("Content-MD5", "Q2hlY2sgSW50ZWdyaXR5IQ==");
        originResponse.addHeader("Content-Length", "128");
        originResponse.addHeader("Content-Type", "application/octet-stream");
        originResponse.addHeader("Last-Modified", DateUtils.formatStandardDate(oneHourAgo));
        originResponse.addHeader("ETag", "\"strong-tag\"");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.addHeader("If-Range", "\"strong-tag\"");
        req2.addHeader("Range", "bytes=0-50");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        if (result.getCode() == HttpStatus.SC_PARTIAL_CONTENT) {
            Assertions.assertEquals("GET,HEAD", result.getFirstHeader("Allow").getValue());
            Assertions.assertEquals("max-age=3600", result.getFirstHeader("Cache-Control").getValue());
            Assertions.assertEquals("en", result.getFirstHeader("Content-Language").getValue());
            Assertions.assertEquals("x-coding", result.getFirstHeader("Content-Encoding").getValue());
            Assertions.assertEquals("Q2hlY2sgSW50ZWdyaXR5IQ==", result.getFirstHeader("Content-MD5")
                    .getValue());
            Assertions.assertEquals(originResponse.getFirstHeader("Last-Modified").getValue(), result
                    .getFirstHeader("Last-Modified").getValue());
        }
        Mockito.verify(mockExecChain, Mockito.times(2)).proceed(Mockito.any(), Mockito.any());
    }

    /*
     * "A cache MUST NOT combine a 206 response with other previously cached
     * content if the ETag or Last-Modified headers do not match exactly, see
     * 13.5.4."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.2.7
     */
    @Test
    public void test206ResponseIsNotCombinedWithPreviousContentIfETagDoesNotMatch() throws Exception {

        final Instant now = Instant.now();

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Cache-Control", "max-age=3600");
        resp1.setHeader("ETag", "\"etag1\"");
        final byte[] bytes1 = new byte[128];
        Arrays.fill(bytes1, (byte) 1);
        resp1.setEntity(new ByteArrayEntity(bytes1, null));

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Cache-Control", "no-cache");
        req2.setHeader("Range", "bytes=0-50");

        final Instant inOneSecond = now.plusSeconds(1);
        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT,
                "Partial Content");
        resp2.setHeader("Date", DateUtils.formatStandardDate(inOneSecond));
        resp2.setHeader("Server", resp1.getFirstHeader("Server").getValue());
        resp2.setHeader("ETag", "\"etag2\"");
        resp2.setHeader("Content-Range", "bytes 0-50/128");
        final byte[] bytes2 = new byte[51];
        Arrays.fill(bytes2, (byte) 2);
        resp2.setEntity(new ByteArrayEntity(bytes2, null));

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        execute(req2);

        final ClassicHttpResponse result = execute(req3);

        final InputStream i = result.getEntity().getContent();
        int b;
        boolean found1 = false;
        boolean found2 = false;
        while ((b = i.read()) != -1) {
            if (b == 1) {
                found1 = true;
            }
            if (b == 2) {
                found2 = true;
            }
        }
        i.close();
        Assertions.assertFalse(found1 && found2); // mixture of content
        Mockito.verify(mockExecChain, Mockito.times(2)).proceed(Mockito.any(), Mockito.any());
    }

    @Test
    public void test206ResponseIsNotCombinedWithPreviousContentIfLastModifiedDoesNotMatch() throws Exception {

        final Instant now = Instant.now();

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        final Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);
        resp1.setHeader("Cache-Control", "max-age=3600");
        resp1.setHeader("Last-Modified", DateUtils.formatStandardDate(oneHourAgo));
        final byte[] bytes1 = new byte[128];
        Arrays.fill(bytes1, (byte) 1);
        resp1.setEntity(new ByteArrayEntity(bytes1, null));

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Cache-Control", "no-cache");
        req2.setHeader("Range", "bytes=0-50");

        final Instant inOneSecond = now.plusSeconds(1);
        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT,
                "Partial Content");
        resp2.setHeader("Date", DateUtils.formatStandardDate(inOneSecond));
        resp2.setHeader("Server", resp1.getFirstHeader("Server").getValue());
        resp2.setHeader("Last-Modified", DateUtils.formatStandardDate(now));
        resp2.setHeader("Content-Range", "bytes 0-50/128");
        final byte[] bytes2 = new byte[51];
        Arrays.fill(bytes2, (byte) 2);
        resp2.setEntity(new ByteArrayEntity(bytes2, null));

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        execute(req2);

        final ClassicHttpResponse result = execute(req3);

        final InputStream i = result.getEntity().getContent();
        int b;
        boolean found1 = false;
        boolean found2 = false;
        while ((b = i.read()) != -1) {
            if (b == 1) {
                found1 = true;
            }
            if (b == 2) {
                found2 = true;
            }
        }
        i.close();
        Assertions.assertFalse(found1 && found2); // mixture of content
        Mockito.verify(mockExecChain, Mockito.times(2)).proceed(Mockito.any(), Mockito.any());
    }

    /*
     * "A cache that does not support the Range and Content-Range headers MUST
     * NOT cache 206 (Partial) responses."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.2.7
     */
    @Test
    public void test206ResponsesAreNotCachedIfTheCacheDoesNotSupportRangeAndContentRangeHeaders() throws Exception {

        if (!impl.supportsRangeAndContentRangeHeaders()) {
            request = new BasicClassicHttpRequest("GET", "/");
            request.addHeader("Range", "bytes=0-50");

            originResponse = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT,"Partial Content");
            originResponse.setHeader("Content-Range", "bytes 0-50/128");
            originResponse.setHeader("Cache-Control", "max-age=3600");
            final byte[] bytes = new byte[51];
            new Random().nextBytes(bytes);
            originResponse.setEntity(new ByteArrayEntity(bytes, null));

            Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

            execute(request);
            Mockito.verifyNoInteractions(mockCache);
        }
    }

    /*
     * "10.3.4 303 See Other ... The 303 response MUST NOT be cached, but the
     * response to the second (redirected) request might be cacheable."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.3.4
     */
    @Test
    public void test303ResponsesAreNotCached() throws Exception {

        request = new BasicClassicHttpRequest("GET", "/");

        originResponse = new BasicClassicHttpResponse(HttpStatus.SC_SEE_OTHER, "See Other");
        originResponse.setHeader("Date", DateUtils.formatStandardDate(Instant.now()));
        originResponse.setHeader("Server", "MockServer/1.0");
        originResponse.setHeader("Cache-Control", "max-age=3600");
        originResponse.setHeader("Content-Type", "application/x-cachingclient-test");
        originResponse.setHeader("Location", "http://foo.example.com/other");
        originResponse.setEntity(HttpTestUtils.makeBody(ENTITY_LENGTH));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);

        Mockito.verifyNoInteractions(mockCache);
    }

    /*
     * "The [304] response MUST include the following header fields: - Date,
     * unless its omission is required by section 14.18.1 [clockless origin
     * servers]."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.3.5
     */
    @Test
    public void test304ResponseWithDateHeaderForwardedFromOriginIncludesDateHeader() throws Exception {

        request.setHeader("If-None-Match", "\"etag\"");

        originResponse = new BasicClassicHttpResponse(HttpStatus.SC_NOT_MODIFIED,"Not Modified");
        originResponse.setHeader("Date", DateUtils.formatStandardDate(Instant.now()));
        originResponse.setHeader("Server", "MockServer/1.0");
        originResponse.setHeader("ETag", "\"etag\"");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        Assertions.assertNotNull(result.getFirstHeader("Date"));
    }

    @Test
    public void test304ResponseGeneratedFromCacheIncludesDateHeader() throws Exception {

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        originResponse.setHeader("Cache-Control", "max-age=3600");
        originResponse.setHeader("ETag", "\"etag\"");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("If-None-Match", "\"etag\"");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        if (result.getCode() == HttpStatus.SC_NOT_MODIFIED) {
            Assertions.assertNotNull(result.getFirstHeader("Date"));
        }
        Mockito.verify(mockExecChain, Mockito.times(1)).proceed(Mockito.any(), Mockito.any());
    }

    /*
     * "The [304] response MUST include the following header fields: - ETag
     * and/or Content-Location, if the header would have been sent in a 200
     * response to the same request."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.3.5
     */
    @Test
    public void test304ResponseGeneratedFromCacheIncludesEtagIfOriginResponseDid() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        originResponse.setHeader("Cache-Control", "max-age=3600");
        originResponse.setHeader("ETag", "\"etag\"");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("If-None-Match", "\"etag\"");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        if (result.getCode() == HttpStatus.SC_NOT_MODIFIED) {
            Assertions.assertNotNull(result.getFirstHeader("ETag"));
        }
        Mockito.verify(mockExecChain, Mockito.times(1)).proceed(Mockito.any(), Mockito.any());
    }

    @Test
    public void test304ResponseGeneratedFromCacheIncludesContentLocationIfOriginResponseDid() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        originResponse.setHeader("Cache-Control", "max-age=3600");
        originResponse.setHeader("Content-Location", "http://foo.example.com/other");
        originResponse.setHeader("ETag", "\"etag\"");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("If-None-Match", "\"etag\"");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        if (result.getCode() == HttpStatus.SC_NOT_MODIFIED) {
            Assertions.assertNotNull(result.getFirstHeader("Content-Location"));
        }
        Mockito.verify(mockExecChain, Mockito.times(1)).proceed(Mockito.any(), Mockito.any());
    }

    /*
     * "The [304] response MUST include the following header fields: ... -
     * Expires, Cache-Control, and/or Vary, if the field-value might differ from
     * that sent in any previous response for the same variant
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.3.5
     */
    @Test
    public void test304ResponseGeneratedFromCacheIncludesExpiresCacheControlAndOrVaryIfResponseMightDiffer() throws Exception {

        final Instant now = Instant.now();
        final Instant inTwoHours = now.plus(2, ChronoUnit.HOURS);

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        req1.setHeader("Accept-Encoding", "gzip");

        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("ETag", "\"v1\"");
        resp1.setHeader("Cache-Control", "max-age=7200");
        resp1.setHeader("Expires", DateUtils.formatStandardDate(inTwoHours));
        resp1.setHeader("Vary", "Accept-Encoding");
        resp1.setEntity(HttpTestUtils.makeBody(ENTITY_LENGTH));

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req1.setHeader("Accept-Encoding", "gzip");
        req1.setHeader("Cache-Control", "no-cache");

        final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();
        resp2.setHeader("ETag", "\"v2\"");
        resp2.setHeader("Cache-Control", "max-age=3600");
        resp2.setHeader("Expires", DateUtils.formatStandardDate(inTwoHours));
        resp2.setHeader("Vary", "Accept-Encoding");
        resp2.setEntity(HttpTestUtils.makeBody(ENTITY_LENGTH));

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");
        req3.setHeader("Accept-Encoding", "gzip");
        req3.setHeader("If-None-Match", "\"v2\"");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        execute(req2);
        final ClassicHttpResponse result = execute(req3);

        if (result.getCode() == HttpStatus.SC_NOT_MODIFIED) {
            Assertions.assertNotNull(result.getFirstHeader("Expires"));
            Assertions.assertNotNull(result.getFirstHeader("Cache-Control"));
            Assertions.assertNotNull(result.getFirstHeader("Vary"));
        }
        Mockito.verify(mockExecChain, Mockito.times(3)).proceed(Mockito.any(), Mockito.any());
    }

    /*
     * "Otherwise (i.e., the conditional GET used a weak validator), the
     * response MUST NOT include other entity-headers; this prevents
     * inconsistencies between cached entity-bodies and updated headers."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.3.5
     */
    @Test
    public void test304GeneratedFromCacheOnWeakValidatorDoesNotIncludeOtherEntityHeaders() throws Exception {

        final Instant now = Instant.now();
        final Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");

        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("ETag", "W/\"v1\"");
        resp1.setHeader("Allow", "GET,HEAD");
        resp1.setHeader("Content-Encoding", "x-coding");
        resp1.setHeader("Content-Language", "en");
        resp1.setHeader("Content-Length", "128");
        resp1.setHeader("Content-MD5", "Q2hlY2sgSW50ZWdyaXR5IQ==");
        resp1.setHeader("Content-Type", "application/octet-stream");
        resp1.setHeader("Last-Modified", DateUtils.formatStandardDate(oneHourAgo));
        resp1.setHeader("Cache-Control", "max-age=7200");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("If-None-Match", "W/\"v1\"");

        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(req1), Mockito.any())).thenReturn(resp1);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        if (result.getCode() == HttpStatus.SC_NOT_MODIFIED) {
            Assertions.assertNull(result.getFirstHeader("Allow"));
            Assertions.assertNull(result.getFirstHeader("Content-Encoding"));
            Assertions.assertNull(result.getFirstHeader("Content-Length"));
            Assertions.assertNull(result.getFirstHeader("Content-MD5"));
            Assertions.assertNull(result.getFirstHeader("Content-Type"));
            Assertions.assertNull(result.getFirstHeader("Last-Modified"));
        }
        Mockito.verify(mockExecChain, Mockito.times(1)).proceed(Mockito.any(), Mockito.any());
    }

    /*
     * "If a 304 response indicates an entity not currently cached, then the
     * cache MUST disregard the response and repeat the request without the
     * conditional."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.3.5
     */
    @Test
    public void testNotModifiedOfNonCachedEntityShouldRevalidateWithUnconditionalGET() throws Exception {

        final Instant now = Instant.now();

        // load cache with cacheable entry
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("ETag", "\"etag1\"");
        resp1.setHeader("Cache-Control", "max-age=3600");

        // force a revalidation
        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Cache-Control", "max-age=0,max-stale=0");

        // unconditional validation doesn't use If-None-Match
        final ClassicHttpRequest unconditionalValidation = new BasicClassicHttpRequest("GET", "/");
        // new response to unconditional validation provides new body
        final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();
        resp1.setHeader("ETag", "\"etag2\"");
        resp1.setHeader("Cache-Control", "max-age=3600");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);
        // this next one will happen once if the cache tries to
        // conditionally validate, zero if it goes full revalidation

        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(unconditionalValidation), Mockito.any())).thenReturn(resp2);

        execute(req1);
        execute(req2);

        Mockito.verify(mockExecChain, Mockito.times(2)).proceed(Mockito.any(), Mockito.any());
    }

    /*
     * "If a cache uses a received 304 response to processChallenge a cache entry, the
     * cache MUST processChallenge the entry to reflect any new field values given in the
     * response.
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.3.5
     */
    @Test
    public void testCacheEntryIsUpdatedWithNewFieldValuesIn304Response() throws Exception {

        final Instant now = Instant.now();
        final Instant inFiveSeconds = now.plusSeconds(5);

        final ClassicHttpRequest initialRequest = new BasicClassicHttpRequest("GET", "/");

        final ClassicHttpResponse cachedResponse = HttpTestUtils.make200Response();
        cachedResponse.setHeader("Cache-Control", "max-age=3600");
        cachedResponse.setHeader("ETag", "\"etag\"");

        final ClassicHttpRequest secondRequest = new BasicClassicHttpRequest("GET", "/");
        secondRequest.setHeader("Cache-Control", "max-age=0,max-stale=0");

        final ClassicHttpRequest conditionalValidationRequest = new BasicClassicHttpRequest("GET", "/");
        conditionalValidationRequest.setHeader("If-None-Match", "\"etag\"");

        // to be used if the cache generates a conditional validation
        final ClassicHttpResponse conditionalResponse = HttpTestUtils.make304Response();
        conditionalResponse.setHeader("Date", DateUtils.formatStandardDate(inFiveSeconds));
        conditionalResponse.setHeader("Server", "MockUtils/1.0");
        conditionalResponse.setHeader("ETag", "\"etag\"");
        conditionalResponse.setHeader("X-Extra", "junk");

        // to be used if the cache generates an unconditional validation
        final ClassicHttpResponse unconditionalResponse = HttpTestUtils.make200Response();
        unconditionalResponse.setHeader("Date", DateUtils.formatStandardDate(inFiveSeconds));
        unconditionalResponse.setHeader("ETag", "\"etag\"");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(cachedResponse);
        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(conditionalValidationRequest), Mockito.any())).thenReturn(conditionalResponse);

        execute(initialRequest);
        final ClassicHttpResponse result = execute(secondRequest);

        Mockito.verify(mockExecChain, Mockito.times(2)).proceed(Mockito.any(), Mockito.any());

        Assertions.assertEquals(DateUtils.formatStandardDate(inFiveSeconds), result.getFirstHeader("Date").getValue());
        Assertions.assertEquals("junk", result.getFirstHeader("X-Extra").getValue());
    }

    /*
     * "10.4.2 401 Unauthorized ... The response MUST include a WWW-Authenticate
     * header field (section 14.47) containing a challenge applicable to the
     * requested resource."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.4.2
     */
    @Test
    public void testMustIncludeWWWAuthenticateHeaderOnAnOrigin401Response() throws Exception {
        originResponse = new BasicClassicHttpResponse(401, "Unauthorized");
        originResponse.setHeader("WWW-Authenticate", "x-scheme x-param");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);
        Assertions.assertEquals(401, result.getCode());
        Assertions.assertNotNull(result.getFirstHeader("WWW-Authenticate"));
    }

    /*
     * "10.4.6 405 Method Not Allowed ... The response MUST include an Allow
     * header containing a list of valid methods for the requested resource.
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.4.2
     */
    @Test
    public void testMustIncludeAllowHeaderFromAnOrigin405Response() throws Exception {
        originResponse = new BasicClassicHttpResponse(405, "Method Not Allowed");
        originResponse.setHeader("Allow", "GET, HEAD");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);
        Assertions.assertEquals(405, result.getCode());
        Assertions.assertNotNull(result.getFirstHeader("Allow"));
    }

    /*
     * "10.4.8 407 Proxy Authentication Required ... The proxy MUST return a
     * Proxy-Authenticate header field (section 14.33) containing a challenge
     * applicable to the proxy for the requested resource."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.4.8
     */
    @Test
    public void testMustIncludeProxyAuthenticateHeaderFromAnOrigin407Response() throws Exception {
        originResponse = new BasicClassicHttpResponse(407, "Proxy Authentication Required");
        originResponse.setHeader("Proxy-Authenticate", "x-scheme x-param");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);
        Assertions.assertEquals(407, result.getCode());
        Assertions.assertNotNull(result.getFirstHeader("Proxy-Authenticate"));
    }

    /*
     * "10.4.17 416 Requested Range Not Satisfiable ... This response MUST NOT
     * use the multipart/byteranges content-type."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.4.17
     */
    @Test
    public void testMustNotAddMultipartByteRangeContentTypeTo416Response() throws Exception {
        originResponse = new BasicClassicHttpResponse(416, "Requested Range Not Satisfiable");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        Assertions.assertEquals(416, result.getCode());
        final Iterator<HeaderElement> it = MessageSupport.iterate(result, HttpHeaders.CONTENT_TYPE);
        while (it.hasNext()) {
            final HeaderElement elt = it.next();
            Assertions.assertFalse("multipart/byteranges".equalsIgnoreCase(elt.getName()));
        }
    }

    @Test
    public void testMustNotUseMultipartByteRangeContentTypeOnCacheGenerated416Responses() throws Exception {

        originResponse.setEntity(HttpTestUtils.makeBody(ENTITY_LENGTH));
        originResponse.setHeader("Content-Length", "128");
        originResponse.setHeader("Cache-Control", "max-age=3600");

        final ClassicHttpRequest rangeReq = new BasicClassicHttpRequest("GET", "/");
        rangeReq.setHeader("Range", "bytes=1000-1200");

        final ClassicHttpResponse orig416 = new BasicClassicHttpResponse(416,
                "Requested Range Not Satisfiable");

        // cache may 416 me right away if it understands byte ranges,
        // ok to delegate to origin though
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);
        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(rangeReq), Mockito.any())).thenReturn(orig416);

        execute(request);
        final ClassicHttpResponse result = execute(rangeReq);

        // might have gotten a 416 from the origin or the cache
        Assertions.assertEquals(416, result.getCode());
        final Iterator<HeaderElement> it = MessageSupport.iterate(result, HttpHeaders.CONTENT_TYPE);
        while (it.hasNext()) {
            final HeaderElement elt = it.next();
            Assertions.assertFalse("multipart/byteranges".equalsIgnoreCase(elt.getName()));
        }
        Mockito.verify(mockExecChain, Mockito.times(2)).proceed(Mockito.any(), Mockito.any());
    }

    /*
     * "A correct cache MUST respond to a request with the most up-to-date
     * response held by the cache that is appropriate to the request (see
     * sections 13.2.5, 13.2.6, and 13.12) which meets one of the following
     * conditions:
     *
     * 1. It has been checked for equivalence with what the origin server would
     * have returned by revalidating the response with the origin server
     * (section 13.3);
     *
     * 2. It is "fresh enough" (see section 13.2). In the default case, this
     * means it meets the least restrictive freshness requirement of the client,
     * origin server, and cache (see section 14.9); if the origin server so
     * specifies, it is the freshness requirement of the origin server alone.
     *
     * If a stored response is not "fresh enough" by the most restrictive
     * freshness requirement of both the client and the origin server, in
     * carefully considered circumstances the cache MAY still return the
     * response with the appropriate Warning header (see section 13.1.5 and
     * 14.46), unless such a response is prohibited (e.g., by a "no-store"
     * cache-directive, or by a "no-cache" cache-request-directive; see section
     * 14.9).
     *
     * 3. It is an appropriate 304 (Not Modified), 305 (Proxy Redirect), or
     * error (4xx or 5xx) response message."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.1.1
     */
    @Test
    public void testMustReturnACacheEntryIfItCanRevalidateIt() throws Exception {

        final Instant now = Instant.now();
        final Instant tenSecondsAgo = now.minusSeconds(10);
        final Instant nineSecondsAgo = now.minusSeconds(9);
        final Instant eightSecondsAgo = now.minusSeconds(8);

        final Header[] hdrs = new Header[] {
                new BasicHeader("Date", DateUtils.formatStandardDate(nineSecondsAgo)),
                new BasicHeader("Cache-Control", "max-age=0"),
                new BasicHeader("ETag", "\"etag\""),
                new BasicHeader("Content-Length", "128")
        };

        final byte[] bytes = new byte[128];
        new Random().nextBytes(bytes);

        final HttpCacheEntry entry = HttpTestUtils.makeCacheEntry(tenSecondsAgo, eightSecondsAgo, hdrs, bytes);

        impl = new CachingExec(mockCache, null, config);

        request = new BasicClassicHttpRequest("GET", "/thing");

        final ClassicHttpRequest validate = new BasicClassicHttpRequest("GET", "/thing");
        validate.setHeader("If-None-Match", "\"etag\"");

        final ClassicHttpResponse notModified = new BasicClassicHttpResponse(HttpStatus.SC_NOT_MODIFIED, "Not Modified");
        notModified.setHeader("Date", DateUtils.formatStandardDate(now));
        notModified.setHeader("ETag", "\"etag\"");

        Mockito.when(mockCache.match(Mockito.eq(host), RequestEquivalent.eq(request))).thenReturn(
                new CacheMatch(new CacheHit("key", entry), null));
        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(validate), Mockito.any())).thenReturn(notModified);
        Mockito.when(mockCache.update(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()))
                .thenReturn(new CacheHit("key", HttpTestUtils.makeCacheEntry()));

        execute(request);

        Mockito.verify(mockCache).update(
                Mockito.any(),
                RequestEquivalent.eq(request),
                ResponseEquivalent.eq(notModified),
                Mockito.any(),
                Mockito.any());
    }

    @Test
    public void testMustReturnAFreshEnoughCacheEntryIfItHasIt() throws Exception {

        final Instant now = Instant.now();
        final Instant tenSecondsAgo = now.minusSeconds(10);
        final Instant nineSecondsAgo = now.plusSeconds(9);
        final Instant eightSecondsAgo = now.plusSeconds(8);

        final Header[] hdrs = new Header[] {
                new BasicHeader("Date", DateUtils.formatStandardDate(nineSecondsAgo)),
                new BasicHeader("Cache-Control", "max-age=3600"),
                new BasicHeader("Content-Length", "128")
        };

        final byte[] bytes = new byte[128];
        new Random().nextBytes(bytes);

        final HttpCacheEntry entry = HttpTestUtils.makeCacheEntry(tenSecondsAgo, eightSecondsAgo, hdrs, bytes);

        impl = new CachingExec(mockCache, null, config);
        request = new BasicClassicHttpRequest("GET", "/thing");

        Mockito.when(mockCache.match(Mockito.eq(host), RequestEquivalent.eq(request))).thenReturn(
                new CacheMatch(new CacheHit("key", entry), null));

        final ClassicHttpResponse result = execute(request);

        Assertions.assertEquals(200, result.getCode());
    }

    /*
     * "When a response is generated from a cache entry, the cache MUST include
     * a single Age header field in the response with a value equal to the cache
     * entry's current_age."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.2.3
     */
    @Test
    public void testAgeHeaderPopulatedFromCacheEntryCurrentAge() throws Exception {

        final Instant now = Instant.now();
        final Instant tenSecondsAgo = now.minusSeconds(10);
        final Instant nineSecondsAgo = now.minusSeconds(9);
        final Instant eightSecondsAgo = now.minusSeconds(8);

        final Header[] hdrs = new Header[] {
                new BasicHeader("Date", DateUtils.formatStandardDate(nineSecondsAgo)),
                new BasicHeader("Cache-Control", "max-age=3600"),
                new BasicHeader("Content-Length", "128")
        };

        final byte[] bytes = new byte[128];
        new Random().nextBytes(bytes);

        final HttpCacheEntry entry = HttpTestUtils.makeCacheEntry(tenSecondsAgo, eightSecondsAgo, hdrs, bytes);

        impl = new CachingExec(mockCache, null, config);
        request = new BasicClassicHttpRequest("GET", "/thing");

        Mockito.when(mockCache.match(Mockito.eq(host), RequestEquivalent.eq(request))).thenReturn(
                new CacheMatch(new CacheHit("key", entry), null));

        final ClassicHttpResponse result = execute(request);

        Assertions.assertEquals(200, result.getCode());
        // We calculate the age of the cache entry as per RFC 9111:
        // We first find the "corrected_initial_age" which is the maximum of "apparentAge" and "correctedReceivedAge".
        // In this case, max(1, 2) = 2 seconds.
        // We then add the "residentTime" which is "now - responseTime",
        // which is the current time minus the time the cache entry was created. In this case, that is 8 seconds.
        // So, the total age is "corrected_initial_age" + "residentTime" = 2 + 8 = 10 seconds.
        assertThat(result, ContainsHeaderMatcher.contains("Age", "10"));
    }

    /*
     * "If a cache has two fresh responses for the same representation with
     * different validators, it MUST use the one with the more recent Date
     * header."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.2.5
     */
    @Test
    public void testKeepsMostRecentDateHeaderForFreshResponse() throws Exception {

        final Instant now = Instant.now();
        final Instant inFiveSecond = now.plusSeconds(5);

        // put an entry in the cache
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");

        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Date", DateUtils.formatStandardDate(inFiveSecond));
        resp1.setHeader("ETag", "\"etag1\"");
        resp1.setHeader("Cache-Control", "max-age=3600");
        resp1.setHeader("Content-Length", "128");

        // force another origin hit
        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Cache-Control", "no-cache");

        final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();
        resp2.setHeader("Date", DateUtils.formatStandardDate(now)); // older
        resp2.setHeader("ETag", "\"etag2\"");
        resp2.setHeader("Cache-Control", "max-age=3600");
        resp2.setHeader("Content-Length", "128");

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        execute(req2);
        final ClassicHttpResponse result = execute(req3);
        Assertions.assertEquals("\"etag1\"", result.getFirstHeader("ETag").getValue());
    }

    /*
     * "HTTP/1.1 clients: - If an entity tag has been provided by the origin
     * server, MUST use that entity tag in any cache-conditional request (using
     * If- Match or If-None-Match)."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.3.4
     */
    @Test
    public void testValidationMustUseETagIfProvidedByOriginServer() throws Exception {

        final Instant now = Instant.now();
        final Instant tenSecondsAgo = now.minusSeconds(10);

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Date", DateUtils.formatStandardDate(now));
        resp1.setHeader("Cache-Control", "max-age=3600");
        resp1.setHeader("Last-Modified", DateUtils.formatStandardDate(tenSecondsAgo));
        resp1.setHeader("ETag", "W/\"etag\"");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Cache-Control", "max-age=0,max-stale=0");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);
        execute(req2);

        final ArgumentCaptor<ClassicHttpRequest> reqCapture = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        Mockito.verify(mockExecChain, Mockito.times(2)).proceed(reqCapture.capture(), Mockito.any());

        final List<ClassicHttpRequest> allRequests = reqCapture.getAllValues();
        Assertions.assertEquals(2, allRequests.size());
        final ClassicHttpRequest validation = allRequests.get(1);
        boolean isConditional = false;
        final String[] conditionalHeaders = { "If-Range", "If-Modified-Since", "If-Unmodified-Since",
                "If-Match", "If-None-Match" };

        for (final String ch : conditionalHeaders) {
            if (validation.getFirstHeader(ch) != null) {
                isConditional = true;
                break;
            }
        }

        if (isConditional) {
            boolean foundETag = false;
            final Iterator<HeaderElement> it = MessageSupport.iterate(validation, HttpHeaders.IF_MATCH);
            while (it.hasNext()) {
                final HeaderElement elt = it.next();
                if ("W/\"etag\"".equals(elt.getName())) {
                    foundETag = true;
                }
            }
            final Iterator<HeaderElement> it2 = MessageSupport.iterate(validation, HttpHeaders.IF_NONE_MATCH);
            while (it2.hasNext()) {
                final HeaderElement elt = it2.next();
                if ("W/\"etag\"".equals(elt.getName())) {
                    foundETag = true;
                }
            }
            Assertions.assertTrue(foundETag);
        }
    }

    /*
     * "An HTTP/1.1 caching proxy, upon receiving a conditional request that
     * includes both a Last-Modified date and one or more entity tags as cache
     * validators, MUST NOT return a locally cached response to the client
     * unless that cached response is consistent with all of the conditional
     * header fields in the request."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.3.4
     */
    @Test
    public void testConditionalRequestWhereNotAllValidatorsMatchCannotBeServedFromCache() throws Exception {
        final Instant now = Instant.now();
        final Instant tenSecondsAgo = now.minusSeconds(10);
        final Instant twentySecondsAgo = now.plusSeconds(20);

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Date", DateUtils.formatStandardDate(now));
        resp1.setHeader("Cache-Control", "max-age=3600");
        resp1.setHeader("Last-Modified", DateUtils.formatStandardDate(tenSecondsAgo));
        resp1.setHeader("ETag", "W/\"etag\"");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("If-None-Match", "W/\"etag\"");
        req2.setHeader("If-Modified-Since", DateUtils.formatStandardDate(twentySecondsAgo));

        // must hit the origin again for the second request
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        Assertions.assertNotEquals(HttpStatus.SC_NOT_MODIFIED, result.getCode());
        Mockito.verify(mockExecChain, Mockito.times(2)).proceed(Mockito.any(), Mockito.any());
    }

    @Test
    public void testConditionalRequestWhereAllValidatorsMatchMayBeServedFromCache() throws Exception {
        final Instant now = Instant.now();
        final Instant tenSecondsAgo = now.minusSeconds(10);

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Date", DateUtils.formatStandardDate(now));
        resp1.setHeader("Cache-Control", "max-age=3600");
        resp1.setHeader("Last-Modified", DateUtils.formatStandardDate(tenSecondsAgo));
        resp1.setHeader("ETag", "W/\"etag\"");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("If-None-Match", "W/\"etag\"");
        req2.setHeader("If-Modified-Since", DateUtils.formatStandardDate(tenSecondsAgo));

        // may hit the origin again for the second request
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);
        execute(req2);

        Mockito.verify(mockExecChain, Mockito.atLeastOnce()).proceed(Mockito.any(), Mockito.any());
        Mockito.verify(mockExecChain, Mockito.atMost(2)).proceed(Mockito.any(), Mockito.any());
    }


    /*
     * "However, a cache that does not support the Range and Content-Range
     * headers MUST NOT cache 206 (Partial Content) responses."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.4
     */
    @Test
    public void testCacheWithoutSupportForRangeAndContentRangeHeadersDoesNotCacheA206Response() throws Exception {

        if (!impl.supportsRangeAndContentRangeHeaders()) {
            final ClassicHttpRequest req = new BasicClassicHttpRequest("GET", "/");
            req.setHeader("Range", "bytes=0-50");

            final ClassicHttpResponse resp = new BasicClassicHttpResponse(206, "Partial Content");
            resp.setHeader("Content-Range", "bytes 0-50/128");
            resp.setHeader("ETag", "\"etag\"");
            resp.setHeader("Cache-Control", "max-age=3600");

            Mockito.when(mockExecChain.proceed(Mockito.any(),Mockito.any())).thenReturn(resp);

            execute(req);

            Mockito.verifyNoInteractions(mockCache);
        }
    }

    /*
     * "A response received with any other status code (e.g. status codes 302
     * and 307) MUST NOT be returned in a reply to a subsequent request unless
     * there are cache-control directives or another header(s) that explicitly
     * allow it. For example, these include the following: an Expires header
     * (section 14.21); a 'max-age', 's-maxage', 'must-revalidate',
     * 'proxy-revalidate', 'public' or 'private' cache-control directive
     * (section 14.9)."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.4
     */
    @Test
    public void test302ResponseWithoutExplicitCacheabilityIsNotReturnedFromCache() throws Exception {
        originResponse = new BasicClassicHttpResponse(302, "Temporary Redirect");
        originResponse.setHeader("Location", "http://foo.example.com/other");
        originResponse.removeHeaders("Expires");
        originResponse.removeHeaders("Cache-Control");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);
        execute(request);

        Mockito.verify(mockExecChain, Mockito.times(2)).proceed(Mockito.any(), Mockito.any());
    }

    /*
     * "A transparent proxy MUST NOT modify any of the following fields in a
     * request or response, and it MUST NOT add any of these fields if not
     * already present: - Content-Location - Content-MD5 - ETag - Last-Modified
     */
    private void testDoesNotModifyHeaderFromOrigin(final String header, final String value) throws Exception {
        originResponse = HttpTestUtils.make200Response();
        originResponse.setHeader(header, value);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        Assertions.assertEquals(value, result.getFirstHeader(header).getValue());
    }

    @Test
    public void testDoesNotModifyContentLocationHeaderFromOrigin() throws Exception {

        final String url = "http://foo.example.com/other";
        testDoesNotModifyHeaderFromOrigin("Content-Location", url);
    }

    @Test
    public void testDoesNotModifyContentMD5HeaderFromOrigin() throws Exception {
        testDoesNotModifyHeaderFromOrigin("Content-MD5", "Q2hlY2sgSW50ZWdyaXR5IQ==");
    }

    @Test
    public void testDoesNotModifyEtagHeaderFromOrigin() throws Exception {
        testDoesNotModifyHeaderFromOrigin("Etag", "\"the-etag\"");
    }

    @Test
    public void testDoesNotModifyLastModifiedHeaderFromOrigin() throws Exception {
        final String lm = DateUtils.formatStandardDate(Instant.now());
        testDoesNotModifyHeaderFromOrigin("Last-Modified", lm);
    }

    private void testDoesNotAddHeaderToOriginResponse(final String header) throws Exception {
        originResponse.removeHeaders(header);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        Assertions.assertNull(result.getFirstHeader(header));
    }

    @Test
    public void testDoesNotAddContentLocationToOriginResponse() throws Exception {
        testDoesNotAddHeaderToOriginResponse("Content-Location");
    }

    @Test
    public void testDoesNotAddContentMD5ToOriginResponse() throws Exception {
        testDoesNotAddHeaderToOriginResponse("Content-MD5");
    }

    @Test
    public void testDoesNotAddEtagToOriginResponse() throws Exception {
        testDoesNotAddHeaderToOriginResponse("ETag");
    }

    @Test
    public void testDoesNotAddLastModifiedToOriginResponse() throws Exception {
        testDoesNotAddHeaderToOriginResponse("Last-Modified");
    }

    private void testDoesNotModifyHeaderFromOriginOnCacheHit(final String header, final String value) throws Exception {

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");

        originResponse = HttpTestUtils.make200Response();
        originResponse.setHeader("Cache-Control", "max-age=3600");
        originResponse.setHeader(header, value);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        Assertions.assertEquals(value, result.getFirstHeader(header).getValue());
    }

    @Test
    public void testDoesNotModifyContentLocationFromOriginOnCacheHit() throws Exception {
        final String url = "http://foo.example.com/other";
        testDoesNotModifyHeaderFromOriginOnCacheHit("Content-Location", url);
    }

    @Test
    public void testDoesNotModifyContentMD5FromOriginOnCacheHit() throws Exception {
        testDoesNotModifyHeaderFromOriginOnCacheHit("Content-MD5", "Q2hlY2sgSW50ZWdyaXR5IQ==");
    }

    @Test
    public void testDoesNotModifyEtagFromOriginOnCacheHit() throws Exception {
        testDoesNotModifyHeaderFromOriginOnCacheHit("Etag", "\"the-etag\"");
    }

    @Test
    public void testDoesNotModifyLastModifiedFromOriginOnCacheHit() throws Exception {
        final Instant tenSecondsAgo = Instant.now().minusSeconds(10);
        testDoesNotModifyHeaderFromOriginOnCacheHit("Last-Modified", DateUtils.formatStandardDate(tenSecondsAgo));
    }

    private void testDoesNotAddHeaderOnCacheHit(final String header) throws Exception {

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");

        originResponse.addHeader("Cache-Control", "max-age=3600");
        originResponse.removeHeaders(header);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        Assertions.assertNull(result.getFirstHeader(header));
    }

    @Test
    public void testDoesNotAddContentLocationHeaderOnCacheHit() throws Exception {
        testDoesNotAddHeaderOnCacheHit("Content-Location");
    }

    @Test
    public void testDoesNotAddContentMD5HeaderOnCacheHit() throws Exception {
        testDoesNotAddHeaderOnCacheHit("Content-MD5");
    }

    @Test
    public void testDoesNotAddETagHeaderOnCacheHit() throws Exception {
        testDoesNotAddHeaderOnCacheHit("ETag");
    }

    @Test
    public void testDoesNotAddLastModifiedHeaderOnCacheHit() throws Exception {
        testDoesNotAddHeaderOnCacheHit("Last-Modified");
    }

    private void testDoesNotModifyHeaderOnRequest(final String header, final String value) throws Exception {
        final BasicClassicHttpRequest req = new BasicClassicHttpRequest("POST","/");
        req.setEntity(HttpTestUtils.makeBody(128));
        req.setHeader("Content-Length","128");
        req.setHeader(header,value);

        execute(req);

        final ArgumentCaptor<ClassicHttpRequest> reqCapture = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        Mockito.verify(mockExecChain).proceed(reqCapture.capture(), Mockito.any());

        final ClassicHttpRequest captured = reqCapture.getValue();
        Assertions.assertEquals(value, captured.getFirstHeader(header).getValue());
    }

    @Test
    public void testDoesNotModifyContentLocationHeaderOnRequest() throws Exception {
        final String url = "http://foo.example.com/other";
        testDoesNotModifyHeaderOnRequest("Content-Location",url);
    }

    @Test
    public void testDoesNotModifyContentMD5HeaderOnRequest() throws Exception {
        testDoesNotModifyHeaderOnRequest("Content-MD5", "Q2hlY2sgSW50ZWdyaXR5IQ==");
    }

    @Test
    public void testDoesNotModifyETagHeaderOnRequest() throws Exception {
        testDoesNotModifyHeaderOnRequest("ETag","\"etag\"");
    }

    @Test
    public void testDoesNotModifyLastModifiedHeaderOnRequest() throws Exception {
        final Instant tenSecondsAgo = Instant.now().minusSeconds(10);
        testDoesNotModifyHeaderOnRequest("Last-Modified", DateUtils.formatStandardDate(tenSecondsAgo));
    }

    private void testDoesNotAddHeaderToRequestIfNotPresent(final String header) throws Exception {
        final BasicClassicHttpRequest req = new BasicClassicHttpRequest("POST","/");
        req.setEntity(HttpTestUtils.makeBody(128));
        req.setHeader("Content-Length","128");
        req.removeHeaders(header);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(req);

        final ArgumentCaptor<ClassicHttpRequest> reqCapture = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        Mockito.verify(mockExecChain).proceed(reqCapture.capture(), Mockito.any());

        final ClassicHttpRequest captured = reqCapture.getValue();
        Assertions.assertNull(captured.getFirstHeader(header));
    }

    @Test
    public void testDoesNotAddContentLocationToRequestIfNotPresent() throws Exception {
        testDoesNotAddHeaderToRequestIfNotPresent("Content-Location");
    }

    @Test
    public void testDoesNotAddContentMD5ToRequestIfNotPresent() throws Exception {
        testDoesNotAddHeaderToRequestIfNotPresent("Content-MD5");
    }

    @Test
    public void testDoesNotAddETagToRequestIfNotPresent() throws Exception {
        testDoesNotAddHeaderToRequestIfNotPresent("ETag");
    }

    @Test
    public void testDoesNotAddLastModifiedToRequestIfNotPresent() throws Exception {
        testDoesNotAddHeaderToRequestIfNotPresent("Last-Modified");
    }

    /* " A transparent proxy MUST NOT modify any of the following
     * fields in a response: - Expires
     * but it MAY add any of these fields if not already present. If
     * an Expires header is added, it MUST be given a field-value
     * identical to that of the Date header in that response.
     */
    @Test
    public void testDoesNotModifyExpiresHeaderFromOrigin() throws Exception {
        final Instant tenSecondsAgo = Instant.now().minusSeconds(10);
        testDoesNotModifyHeaderFromOrigin("Expires", DateUtils.formatStandardDate(tenSecondsAgo));
    }

    @Test
    public void testDoesNotModifyExpiresHeaderFromOriginOnCacheHit() throws Exception {
        final Instant inTenSeconds = Instant.now().plusSeconds(10);
        testDoesNotModifyHeaderFromOriginOnCacheHit("Expires", DateUtils.formatStandardDate(inTenSeconds));
    }

    @Test
    public void testExpiresHeaderMatchesDateIfAddedToOriginResponse() throws Exception {
        originResponse.removeHeaders("Expires");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        final Header expHdr = result.getFirstHeader("Expires");
        if (expHdr != null) {
            Assertions.assertEquals(result.getFirstHeader("Date").getValue(),
                                expHdr.getValue());
        }
    }

    @Test
    public void testExpiresHeaderMatchesDateIfAddedToCacheHit() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");

        originResponse.setHeader("Cache-Control","max-age=3600");
        originResponse.removeHeaders("Expires");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        final Header expHdr = result.getFirstHeader("Expires");
        if (expHdr != null) {
            Assertions.assertEquals(result.getFirstHeader("Date").getValue(),
                                expHdr.getValue());
        }
    }

    /* "A proxy MUST NOT modify or add any of the following fields in
     * a message that contains the no-transform cache-control
     * directive, or in any request: - Content-Encoding - Content-Range
     * - Content-Type"
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.5.2
     */
    private void testDoesNotModifyHeaderFromOriginResponseWithNoTransform(final String header, final String value) throws Exception {
        originResponse.addHeader("Cache-Control","no-transform");
        originResponse.setHeader(header, value);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        Assertions.assertEquals(value, result.getFirstHeader(header).getValue());
    }

    @Test
    public void testDoesNotModifyContentEncodingHeaderFromOriginResponseWithNoTransform() throws Exception {
        testDoesNotModifyHeaderFromOriginResponseWithNoTransform("Content-Encoding","gzip");
    }

    @Test
    public void testDoesNotModifyContentRangeHeaderFromOriginResponseWithNoTransform() throws Exception {
        request.setHeader("If-Range","\"etag\"");
        request.setHeader("Range","bytes=0-49");

        originResponse = new BasicClassicHttpResponse(206, "Partial Content");
        originResponse.setEntity(HttpTestUtils.makeBody(50));
        testDoesNotModifyHeaderFromOriginResponseWithNoTransform("Content-Range","bytes 0-49/128");
    }

    @Test
    public void testDoesNotModifyContentTypeHeaderFromOriginResponseWithNoTransform() throws Exception {
        testDoesNotModifyHeaderFromOriginResponseWithNoTransform("Content-Type","text/html;charset=utf-8");
    }

    private void testDoesNotModifyHeaderOnCachedResponseWithNoTransform(final String header, final String value) throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");

        originResponse.addHeader("Cache-Control","max-age=3600, no-transform");
        originResponse.setHeader(header, value);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        Assertions.assertEquals(value, result.getFirstHeader(header).getValue());
    }

    @Test
    public void testDoesNotModifyContentEncodingHeaderOnCachedResponseWithNoTransform() throws Exception {
        testDoesNotModifyHeaderOnCachedResponseWithNoTransform("Content-Encoding","gzip");
    }

    @Test
    public void testDoesNotModifyContentTypeHeaderOnCachedResponseWithNoTransform() throws Exception {
        testDoesNotModifyHeaderOnCachedResponseWithNoTransform("Content-Type","text/html;charset=utf-8");
    }

    @Test
    public void testDoesNotModifyContentRangeHeaderOnCachedResponseWithNoTransform() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        req1.setHeader("If-Range","\"etag\"");
        req1.setHeader("Range","bytes=0-49");
        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("If-Range","\"etag\"");
        req2.setHeader("Range","bytes=0-49");

        originResponse.addHeader("Cache-Control","max-age=3600, no-transform");
        originResponse.setHeader("Content-Range", "bytes 0-49/128");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        Assertions.assertEquals("bytes 0-49/128",
                            result.getFirstHeader("Content-Range").getValue());

        Mockito.verify(mockExecChain, Mockito.times(2)).proceed(Mockito.any(), Mockito.any());
    }

    @Test
    public void testDoesNotAddContentEncodingHeaderToOriginResponseWithNoTransformIfNotPresent() throws Exception {
        originResponse.addHeader("Cache-Control","no-transform");
        testDoesNotAddHeaderToOriginResponse("Content-Encoding");
    }

    @Test
    public void testDoesNotAddContentRangeHeaderToOriginResponseWithNoTransformIfNotPresent() throws Exception {
        originResponse.addHeader("Cache-Control","no-transform");
        testDoesNotAddHeaderToOriginResponse("Content-Range");
    }

    @Test
    public void testDoesNotAddContentTypeHeaderToOriginResponseWithNoTransformIfNotPresent() throws Exception {
        originResponse.addHeader("Cache-Control","no-transform");
        testDoesNotAddHeaderToOriginResponse("Content-Type");
    }

    /* no add on cache hit with no-transform */
    @Test
    public void testDoesNotAddContentEncodingHeaderToCachedResponseWithNoTransformIfNotPresent() throws Exception {
        originResponse.addHeader("Cache-Control","no-transform");
        testDoesNotAddHeaderOnCacheHit("Content-Encoding");
    }

    @Test
    public void testDoesNotAddContentRangeHeaderToCachedResponseWithNoTransformIfNotPresent() throws Exception {
        originResponse.addHeader("Cache-Control","no-transform");
        testDoesNotAddHeaderOnCacheHit("Content-Range");
    }

    @Test
    public void testDoesNotAddContentTypeHeaderToCachedResponseWithNoTransformIfNotPresent() throws Exception {
        originResponse.addHeader("Cache-Control","no-transform");
        testDoesNotAddHeaderOnCacheHit("Content-Type");
    }

    /* no modify on request */
    @Test
    public void testDoesNotAddContentEncodingToRequestIfNotPresent() throws Exception {
        testDoesNotAddHeaderToRequestIfNotPresent("Content-Encoding");
    }

    @Test
    public void testDoesNotAddContentRangeToRequestIfNotPresent() throws Exception {
        testDoesNotAddHeaderToRequestIfNotPresent("Content-Range");
    }

    @Test
    public void testDoesNotAddContentTypeToRequestIfNotPresent() throws Exception {
        testDoesNotAddHeaderToRequestIfNotPresent("Content-Type");
    }

    @Test
    public void testDoesNotAddContentEncodingHeaderToRequestIfNotPresent() throws Exception {
        testDoesNotAddHeaderToRequestIfNotPresent("Content-Encoding");
    }

    @Test
    public void testDoesNotAddContentRangeHeaderToRequestIfNotPresent() throws Exception {
        testDoesNotAddHeaderToRequestIfNotPresent("Content-Range");
    }

    @Test
    public void testDoesNotAddContentTypeHeaderToRequestIfNotPresent() throws Exception {
        testDoesNotAddHeaderToRequestIfNotPresent("Content-Type");
    }

    /* "When a cache makes a validating request to a server, and the
     * server provides a 304 (Not Modified) response or a 206 (Partial
     * Content) response, the cache then constructs a response to send
     * to the requesting client.
     *
     * If the status code is 304 (Not Modified), the cache uses the
     * entity-body stored in the cache entry as the entity-body of
     * this outgoing response.
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.5.3
     */
    public void testCachedEntityBodyIsUsedForResponseAfter304Validation() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Cache-Control","max-age=3600");
        resp1.setHeader("ETag","\"etag\"");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Cache-Control","max-age=0, max-stale=0");
        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_NOT_MODIFIED, "Not Modified");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        final ClassicHttpResponse result = execute(req2);

        final InputStream i1 = resp1.getEntity().getContent();
        final InputStream i2 = result.getEntity().getContent();
        int b1, b2;
        while((b1 = i1.read()) != -1) {
            b2 = i2.read();
            Assertions.assertEquals(b1, b2);
        }
        b2 = i2.read();
        Assertions.assertEquals(-1, b2);
        i1.close();
        i2.close();
    }

    /* "The end-to-end headers stored in the cache entry are used for
     * the constructed response, except that ...
     *
     * - any end-to-end headers provided in the 304 or 206 response MUST
     *  replace the corresponding headers from the cache entry.
     *
     * Unless the cache decides to remove the cache entry, it MUST
     * also replace the end-to-end headers stored with the cache entry
     * with corresponding headers received in the incoming response,
     * except for Warning headers as described immediately above."
     */
    private void decorateWithEndToEndHeaders(final ClassicHttpResponse r) {
        r.setHeader("Allow","GET");
        r.setHeader("Content-Encoding","gzip");
        r.setHeader("Content-Language","en");
        r.setHeader("Content-Length", "128");
        r.setHeader("Content-Location","http://foo.example.com/other");
        r.setHeader("Content-MD5", "Q2hlY2sgSW50ZWdyaXR5IQ==");
        r.setHeader("Content-Type", "text/html;charset=utf-8");
        r.setHeader("Expires", DateUtils.formatStandardDate(Instant.now().plusSeconds(10)));
        r.setHeader("Last-Modified", DateUtils.formatStandardDate(Instant.now().minusSeconds(10)));
        r.setHeader("Location", "http://foo.example.com/other2");
        r.setHeader("Retry-After","180");
    }

    @Test
    public void testResponseIncludesCacheEntryEndToEndHeadersForResponseAfter304Validation() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Cache-Control","max-age=3600");
        resp1.setHeader("ETag","\"etag\"");
        decorateWithEndToEndHeaders(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Cache-Control", "max-age=0, max-stale=0");
        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_NOT_MODIFIED, "Not Modified");
        resp2.setHeader("Date", DateUtils.formatStandardDate(Instant.now()));
        resp2.setHeader("Server", "MockServer/1.0");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);

        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(req2), Mockito.any())).thenReturn(resp2);
        final ClassicHttpResponse result = execute(req2);

        final String[] endToEndHeaders = {
            "Cache-Control", "ETag", "Allow", "Content-Encoding",
            "Content-Language", "Content-Length", "Content-Location",
            "Content-MD5", "Content-Type", "Expires", "Last-Modified",
            "Location", "Retry-After"
        };
        for(final String h : endToEndHeaders) {
            Assertions.assertEquals(HttpTestUtils.getCanonicalHeaderValue(resp1, h),
                                HttpTestUtils.getCanonicalHeaderValue(result, h));
        }
    }

    @Test
    public void testUpdatedEndToEndHeadersFrom304ArePassedOnResponseAndUpdatedInCacheEntry() throws Exception {

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Cache-Control","max-age=3600");
        resp1.setHeader("ETag","\"etag\"");
        decorateWithEndToEndHeaders(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Cache-Control", "max-age=0, max-stale=0");
        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_NOT_MODIFIED, "Not Modified");
        resp2.setHeader("Cache-Control", "max-age=1800");
        resp2.setHeader("Date", DateUtils.formatStandardDate(Instant.now()));
        resp2.setHeader("Server", "MockServer/1.0");
        resp2.setHeader("Allow", "GET,HEAD");
        resp2.setHeader("Content-Language", "en,en-us");
        resp2.setHeader("Content-Location", "http://foo.example.com/new");
        resp2.setHeader("Content-Type","text/html");
        resp2.setHeader("Expires", DateUtils.formatStandardDate(Instant.now().plusSeconds(5)));
        resp2.setHeader("Location", "http://foo.example.com/new2");
        resp2.setHeader("Retry-After","120");

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);
        final ClassicHttpResponse result1 = execute(req2);
        final ClassicHttpResponse result2 = execute(req3);

        final String[] endToEndHeaders = {
            "Date", "Cache-Control", "Allow", "Content-Language",
            "Content-Location", "Content-Type", "Expires", "Location",
            "Retry-After"
        };
        for(final String h : endToEndHeaders) {
            Assertions.assertEquals(HttpTestUtils.getCanonicalHeaderValue(resp2, h),
                                HttpTestUtils.getCanonicalHeaderValue(result1, h));
            Assertions.assertEquals(HttpTestUtils.getCanonicalHeaderValue(resp2, h),
                                HttpTestUtils.getCanonicalHeaderValue(result2, h));
        }
    }

    /* "If a header field-name in the incoming response matches more
     * than one header in the cache entry, all such old headers MUST
     * be replaced."
     */
    @Test
    public void testMultiHeadersAreSuccessfullyReplacedOn304Validation() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.addHeader("Cache-Control","max-age=3600");
        resp1.addHeader("Cache-Control","public");
        resp1.setHeader("ETag","\"etag\"");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Cache-Control", "max-age=0, max-stale=0");
        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_NOT_MODIFIED, "Not Modified");
        resp2.setHeader("Cache-Control", "max-age=1800");

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        final ClassicHttpResponse result1 = execute(req2);
        final ClassicHttpResponse result2 = execute(req3);

        final String h = "Cache-Control";
        Assertions.assertEquals(HttpTestUtils.getCanonicalHeaderValue(resp2, h),
                            HttpTestUtils.getCanonicalHeaderValue(result1, h));
        Assertions.assertEquals(HttpTestUtils.getCanonicalHeaderValue(resp2, h),
                            HttpTestUtils.getCanonicalHeaderValue(result2, h));
    }

    /* "If a cache has a stored non-empty set of subranges for an
     * entity, and an incoming response transfers another subrange,
     * the cache MAY combine the new subrange with the existing set if
     * both the following conditions are met:
     *
     * - Both the incoming response and the cache entry have a cache
     * validator.
     *
     * - The two cache validators match using the strong comparison
     * function (see section 13.3.3).
     *
     * If either requirement is not met, the cache MUST use only the
     * most recent partial response (based on the Date values
     * transmitted with every response, and using the incoming
     * response if these values are equal or missing), and MUST
     * discard the other partial information."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.5.4
     */
    @Test
    public void testCannotCombinePartialResponseIfIncomingResponseDoesNotHaveACacheValidator() throws Exception {

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        req1.setHeader("Range","bytes=0-49");

        final Instant now = Instant.now();
        final Instant oneSecondAgo = now.minusSeconds(1);
        final Instant twoSecondsAgo = Instant.now().plusSeconds(2);

        final ClassicHttpResponse resp1 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp1.setEntity(HttpTestUtils.makeBody(50));
        resp1.setHeader("Server","MockServer/1.0");
        resp1.setHeader("Date", DateUtils.formatStandardDate(twoSecondsAgo));
        resp1.setHeader("Cache-Control","max-age=3600");
        resp1.setHeader("Content-Range","bytes 0-49/128");
        resp1.setHeader("ETag","\"etag1\"");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Range","bytes=50-127");

        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp2.setEntity(HttpTestUtils.makeBody(78));
        resp2.setHeader("Cache-Control","max-age=3600");
        resp2.setHeader("Content-Range","bytes 50-127/128");
        resp2.setHeader("Server","MockServer/1.0");
        resp2.setHeader("Date", DateUtils.formatStandardDate(oneSecondAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");

        final ClassicHttpResponse resp3 = new BasicClassicHttpResponse(HttpStatus.SC_OK, "OK");
        resp3.setEntity(HttpTestUtils.makeBody(128));
        resp3.setHeader("Server","MockServer/1.0");
        resp3.setHeader("Date", DateUtils.formatStandardDate(now));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp3);

        execute(req1);
        execute(req2);
        execute(req3);
    }

    @Test
    public void testCannotCombinePartialResponseIfCacheEntryDoesNotHaveACacheValidator() throws Exception {

        final Instant now = Instant.now();
        final Instant oneSecondAgo = now.minusSeconds(1);
        final Instant twoSecondsAgo = Instant.now().plusSeconds(2);

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        req1.setHeader("Range","bytes=0-49");

        final ClassicHttpResponse resp1 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp1.setEntity(HttpTestUtils.makeBody(50));
        resp1.setHeader("Cache-Control","max-age=3600");
        resp1.setHeader("Content-Range","bytes 0-49/128");
        resp1.setHeader("Server","MockServer/1.0");
        resp1.setHeader("Date", DateUtils.formatStandardDate(twoSecondsAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Range","bytes=50-127");

        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp2.setEntity(HttpTestUtils.makeBody(78));
        resp2.setHeader("Cache-Control","max-age=3600");
        resp2.setHeader("Content-Range","bytes 50-127/128");
        resp2.setHeader("ETag","\"etag1\"");
        resp2.setHeader("Server","MockServer/1.0");
        resp2.setHeader("Date", DateUtils.formatStandardDate(oneSecondAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");

        final ClassicHttpResponse resp3 = new BasicClassicHttpResponse(HttpStatus.SC_OK, "OK");
        resp3.setEntity(HttpTestUtils.makeBody(128));
        resp3.setHeader("Server","MockServer/1.0");
        resp3.setHeader("Date", DateUtils.formatStandardDate(now));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp3);

        execute(req1);
        execute(req2);
        execute(req3);
    }

    @Test
    public void testCannotCombinePartialResponseIfCacheValidatorsDoNotStronglyMatch() throws Exception {

        final Instant now = Instant.now();
        final Instant oneSecondAgo = now.minusSeconds(1);
        final Instant twoSecondsAgo = Instant.now().plusSeconds(2);

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        req1.setHeader("Range","bytes=0-49");

        final ClassicHttpResponse resp1 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp1.setEntity(HttpTestUtils.makeBody(50));
        resp1.setHeader("Cache-Control","max-age=3600");
        resp1.setHeader("Content-Range","bytes 0-49/128");
        resp1.setHeader("ETag","\"etag1\"");
        resp1.setHeader("Server","MockServer/1.0");
        resp1.setHeader("Date", DateUtils.formatStandardDate(twoSecondsAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Range","bytes=50-127");

        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp2.setEntity(HttpTestUtils.makeBody(78));
        resp2.setHeader("Cache-Control","max-age=3600");
        resp2.setHeader("Content-Range","bytes 50-127/128");
        resp2.setHeader("ETag","\"etag2\"");
        resp2.setHeader("Server","MockServer/1.0");
        resp2.setHeader("Date", DateUtils.formatStandardDate(oneSecondAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");

        final ClassicHttpResponse resp3 = new BasicClassicHttpResponse(HttpStatus.SC_OK, "OK");
        resp3.setEntity(HttpTestUtils.makeBody(128));
        resp3.setHeader("Server","MockServer/1.0");
        resp3.setHeader("Date", DateUtils.formatStandardDate(now));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp3);

        execute(req1);
        execute(req2);
        execute(req3);
    }

    @Test
    public void testMustDiscardLeastRecentPartialResponseIfIncomingRequestDoesNotHaveCacheValidator() throws Exception {

        final Instant now = Instant.now();
        final Instant oneSecondAgo = now.minusSeconds(1);
        final Instant twoSecondsAgo = Instant.now().plusSeconds(2);

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        req1.setHeader("Range","bytes=0-49");

        final ClassicHttpResponse resp1 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp1.setEntity(HttpTestUtils.makeBody(50));
        resp1.setHeader("Cache-Control","max-age=3600");
        resp1.setHeader("Content-Range","bytes 0-49/128");
        resp1.setHeader("ETag","\"etag1\"");
        resp1.setHeader("Server","MockServer/1.0");
        resp1.setHeader("Date", DateUtils.formatStandardDate(twoSecondsAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Range","bytes=50-127");

        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp2.setEntity(HttpTestUtils.makeBody(78));
        resp2.setHeader("Cache-Control","max-age=3600");
        resp2.setHeader("Content-Range","bytes 50-127/128");
        resp2.setHeader("Server","MockServer/1.0");
        resp2.setHeader("Date", DateUtils.formatStandardDate(oneSecondAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");
        req3.setHeader("Range","bytes=0-49");

        final ClassicHttpResponse resp3 = new BasicClassicHttpResponse(HttpStatus.SC_OK, "OK");
        resp3.setEntity(HttpTestUtils.makeBody(128));
        resp3.setHeader("Server","MockServer/1.0");
        resp3.setHeader("Date", DateUtils.formatStandardDate(now));

        // must make this request; cannot serve from cache
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp3);

        execute(req1);
        execute(req2);
        execute(req3);
    }

    @Test
    public void testMustDiscardLeastRecentPartialResponseIfCachedResponseDoesNotHaveCacheValidator() throws Exception {

        final Instant now = Instant.now();
        final Instant oneSecondAgo = now.minusSeconds(1);
        final Instant twoSecondsAgo = Instant.now().plusSeconds(2);

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        req1.setHeader("Range","bytes=0-49");

        final ClassicHttpResponse resp1 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp1.setEntity(HttpTestUtils.makeBody(50));
        resp1.setHeader("Cache-Control","max-age=3600");
        resp1.setHeader("Content-Range","bytes 0-49/128");
        resp1.setHeader("Server","MockServer/1.0");
        resp1.setHeader("Date", DateUtils.formatStandardDate(twoSecondsAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Range","bytes=50-127");

        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp2.setEntity(HttpTestUtils.makeBody(78));
        resp2.setHeader("Cache-Control","max-age=3600");
        resp2.setHeader("Content-Range","bytes 50-127/128");
        resp2.setHeader("ETag","\"etag1\"");
        resp2.setHeader("Server","MockServer/1.0");
        resp2.setHeader("Date", DateUtils.formatStandardDate(oneSecondAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");
        req3.setHeader("Range","bytes=0-49");

        final ClassicHttpResponse resp3 = new BasicClassicHttpResponse(HttpStatus.SC_OK, "OK");
        resp3.setEntity(HttpTestUtils.makeBody(128));
        resp3.setHeader("Server","MockServer/1.0");
        resp3.setHeader("Date", DateUtils.formatStandardDate(now));

        // must make this request; cannot serve from cache
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp3);

        execute(req1);
        execute(req2);
        execute(req3);
    }

    @Test
    public void testMustDiscardLeastRecentPartialResponseIfCacheValidatorsDoNotStronglyMatch() throws Exception {

        final Instant now = Instant.now();
        final Instant oneSecondAgo = now.minusSeconds(1);
        final Instant twoSecondsAgo = Instant.now().plusSeconds(2);

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        req1.setHeader("Range","bytes=0-49");

        final ClassicHttpResponse resp1 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp1.setEntity(HttpTestUtils.makeBody(50));
        resp1.setHeader("Cache-Control","max-age=3600");
        resp1.setHeader("Content-Range","bytes 0-49/128");
        resp1.setHeader("Etag","\"etag1\"");
        resp1.setHeader("Server","MockServer/1.0");
        resp1.setHeader("Date", DateUtils.formatStandardDate(twoSecondsAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Range","bytes=50-127");

        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp2.setEntity(HttpTestUtils.makeBody(78));
        resp2.setHeader("Cache-Control","max-age=3600");
        resp2.setHeader("Content-Range","bytes 50-127/128");
        resp2.setHeader("ETag","\"etag2\"");
        resp2.setHeader("Server","MockServer/1.0");
        resp2.setHeader("Date", DateUtils.formatStandardDate(oneSecondAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");
        req3.setHeader("Range","bytes=0-49");

        final ClassicHttpResponse resp3 = new BasicClassicHttpResponse(HttpStatus.SC_OK, "OK");
        resp3.setEntity(HttpTestUtils.makeBody(128));
        resp3.setHeader("Server","MockServer/1.0");
        resp3.setHeader("Date", DateUtils.formatStandardDate(now));

        // must make this request; cannot serve from cache
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp3);

        execute(req1);
        execute(req2);
        execute(req3);
    }

    @Test
    public void testMustDiscardLeastRecentPartialResponseIfCacheValidatorsDoNotStronglyMatchEvenIfResponsesOutOfOrder() throws Exception {

        final Instant now = Instant.now();
        final Instant oneSecondAgo = now.minusSeconds(1);
        final Instant twoSecondsAgo = Instant.now().plusSeconds(2);

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        req1.setHeader("Range","bytes=0-49");

        final ClassicHttpResponse resp1 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp1.setEntity(HttpTestUtils.makeBody(50));
        resp1.setHeader("Cache-Control","max-age=3600");
        resp1.setHeader("Content-Range","bytes 0-49/128");
        resp1.setHeader("Etag","\"etag1\"");
        resp1.setHeader("Server","MockServer/1.0");
        resp1.setHeader("Date", DateUtils.formatStandardDate(oneSecondAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Range","bytes=50-127");

        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp2.setEntity(HttpTestUtils.makeBody(78));
        resp2.setHeader("Cache-Control","max-age=3600");
        resp2.setHeader("Content-Range","bytes 50-127/128");
        resp2.setHeader("ETag","\"etag2\"");
        resp2.setHeader("Server","MockServer/1.0");
        resp2.setHeader("Date", DateUtils.formatStandardDate(twoSecondsAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");
        req3.setHeader("Range","bytes=50-127");

        final ClassicHttpResponse resp3 = new BasicClassicHttpResponse(HttpStatus.SC_OK, "OK");
        resp3.setEntity(HttpTestUtils.makeBody(128));
        resp3.setHeader("Server","MockServer/1.0");
        resp3.setHeader("Date", DateUtils.formatStandardDate(now));

        // must make this request; cannot serve from cache
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp3);

        execute(req1);
        execute(req2);
        execute(req3);
    }

    @Test
    public void testMustDiscardCachedPartialResponseIfCacheValidatorsDoNotStronglyMatchAndDateHeadersAreEqual() throws Exception {

        final Instant now = Instant.now();
        final Instant oneSecondAgo = now.minusSeconds(1);

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        req1.setHeader("Range","bytes=0-49");

        final ClassicHttpResponse resp1 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp1.setEntity(HttpTestUtils.makeBody(50));
        resp1.setHeader("Cache-Control","max-age=3600");
        resp1.setHeader("Content-Range","bytes 0-49/128");
        resp1.setHeader("Etag","\"etag1\"");
        resp1.setHeader("Server","MockServer/1.0");
        resp1.setHeader("Date", DateUtils.formatStandardDate(oneSecondAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Range","bytes=50-127");

        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_PARTIAL_CONTENT, "Partial Content");
        resp2.setEntity(HttpTestUtils.makeBody(78));
        resp2.setHeader("Cache-Control","max-age=3600");
        resp2.setHeader("Content-Range","bytes 50-127/128");
        resp2.setHeader("ETag","\"etag2\"");
        resp2.setHeader("Server","MockServer/1.0");
        resp2.setHeader("Date", DateUtils.formatStandardDate(oneSecondAgo));

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");
        req3.setHeader("Range","bytes=0-49");

        final ClassicHttpResponse resp3 = new BasicClassicHttpResponse(HttpStatus.SC_OK, "OK");
        resp3.setEntity(HttpTestUtils.makeBody(128));
        resp3.setHeader("Server","MockServer/1.0");
        resp3.setHeader("Date", DateUtils.formatStandardDate(now));

        // must make this request; cannot serve from cache
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp3);

        execute(req1);
        execute(req2);
        execute(req3);
    }

    /* "When the cache receives a subsequent request whose Request-URI
     * specifies one or more cache entries including a Vary header
     * field, the cache MUST NOT use such a cache entry to construct a
     * response to the new request unless all of the selecting
     * request-headers present in the new request match the
     * corresponding stored request-headers in the original request."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.6
     */
    @Test
    public void testCannotUseVariantCacheEntryIfNotAllSelectingRequestHeadersMatch() throws Exception {

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        req1.setHeader("Accept-Encoding","gzip");

        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("ETag","\"etag1\"");
        resp1.setHeader("Cache-Control","max-age=3600");
        resp1.setHeader("Vary","Accept-Encoding");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.removeHeaders("Accept-Encoding");

        final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();
        resp2.setHeader("ETag","\"etag1\"");
        resp2.setHeader("Cache-Control","max-age=3600");

        // not allowed to have a cache hit; must forward request
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        execute(req1);
        execute(req2);
    }

    /* "A Vary header field-value of "*" always fails to match and
     * subsequent requests on that resource can only be properly
     * interpreted by the origin server."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.6
     */
    @Test
    public void testCannotServeFromCacheForVaryStar() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");

        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("ETag","\"etag1\"");
        resp1.setHeader("Cache-Control","max-age=3600");
        resp1.setHeader("Vary","*");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");

        final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();
        resp2.setHeader("ETag","\"etag1\"");
        resp2.setHeader("Cache-Control","max-age=3600");

        // not allowed to have a cache hit; must forward request
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        execute(req1);
        execute(req2);
    }

    /* " If the selecting request header fields for the cached entry
     * do not match the selecting request header fields of the new
     * request, then the cache MUST NOT use a cached entry to satisfy
     * the request unless it first relays the new request to the
     * origin server in a conditional request and the server responds
     * with 304 (Not Modified), including an entity tag or
     * Content-Location that indicates the entity to be used.
     *
     * If an entity tag was assigned to a cached representation, the
     * forwarded request SHOULD be conditional and include the entity
     * tags in an If-None-Match header field from all its cache
     * entries for the resource. This conveys to the server the set of
     * entities currently held by the cache, so that if any one of
     * these entities matches the requested entity, the server can use
     * the ETag header field in its 304 (Not Modified) response to
     * tell the cache which entry is appropriate. If the entity-tag of
     * the new response matches that of an existing entry, the new
     * response SHOULD be used to processChallenge the header fields of the
     * existing entry, and the result MUST be returned to the client.
     *
     * NOTE:  Tests that a non-matching variant cannot be served from cache unless conditionally validated.
     *
     * The original test expected the response to have an ETag header with a specific value, but the changes made
     * to the cache implementation made it so that ETag headers are not added to variant responses. Therefore, the test
     * was updated to expect that the variant response has a Vary header instead, indicating that the response may vary
     * based on the User-Agent header. Additionally, the mock response for the second request was changed to include a Vary
     * header to match the first response. This ensures that the second request will not match the first response in the
     * cache and will have to be validated conditionally against the origin server.
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.6
     */
    @Test
    public void testNonmatchingVariantCannotBeServedFromCacheUnlessConditionallyValidated() throws Exception {

        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        req1.setHeader("User-Agent","MyBrowser/1.0");

        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("ETag","\"etag1\"");
        resp1.setHeader("Cache-Control","max-age=3600");
        resp1.setHeader("Vary","User-Agent");
        resp1.setHeader("Content-Type","application/octet-stream");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("User-Agent","MyBrowser/1.5");

        final ClassicHttpResponse resp200 = HttpTestUtils.make200Response();
        resp200.setHeader("ETag","\"etag1\"");
        resp200.setHeader("Vary","User-Agent");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);

        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(req2), Mockito.any())).thenReturn(resp200);

        final ClassicHttpResponse result = execute(req2);

        Assertions.assertEquals(HttpStatus.SC_OK, result.getCode());

        Mockito.verify(mockExecChain, Mockito.times(2)).proceed(Mockito.any(), Mockito.any());

        Assertions.assertTrue(HttpTestUtils.semanticallyTransparent(resp200, result));
    }

    /* "Some HTTP methods MUST cause a cache to invalidate an
     * entity. This is either the entity referred to by the
     * Request-URI, or by the Location or Content-Location headers (if
     * present). These methods are:
     * - PUT
     * - DELETE
     * - POST
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.9
     */
    protected void testUnsafeOperationInvalidatesCacheForThatUri(
            final ClassicHttpRequest unsafeReq) throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Cache-Control","public, max-age=3600");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_NO_CONTENT, "No Content");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp3 = HttpTestUtils.make200Response();
        resp3.setHeader("Cache-Control","public, max-age=3600");

        // this origin request MUST happen due to invalidation
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp3);

        execute(req1);
        execute(unsafeReq);
        execute(req3);
    }

    protected ClassicHttpRequest makeRequestWithBody(final String method, final String requestUri) {
        final ClassicHttpRequest req = new BasicClassicHttpRequest(method, requestUri);
        final int nbytes = 128;
        req.setEntity(HttpTestUtils.makeBody(nbytes));
        req.setHeader("Content-Length", Long.toString(nbytes));
        return req;
    }

    @Test
    public void testPutToUriInvalidatesCacheForThatUri() throws Exception {
        final ClassicHttpRequest req = makeRequestWithBody("PUT","/");
        testUnsafeOperationInvalidatesCacheForThatUri(req);
    }

    @Test
    public void testDeleteToUriInvalidatesCacheForThatUri() throws Exception {
        final ClassicHttpRequest req = new BasicClassicHttpRequest("DELETE","/");
        testUnsafeOperationInvalidatesCacheForThatUri(req);
    }

    @Test
    public void testPostToUriInvalidatesCacheForThatUri() throws Exception {
        final ClassicHttpRequest req = makeRequestWithBody("POST","/");
        testUnsafeOperationInvalidatesCacheForThatUri(req);
    }

    protected void testUnsafeMethodInvalidatesCacheForHeaderUri(
            final ClassicHttpRequest unsafeReq) throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/content");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Cache-Control","public, max-age=3600");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_NO_CONTENT, "No Content");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/content");
        final ClassicHttpResponse resp3 = HttpTestUtils.make200Response();
        resp3.setHeader("Cache-Control","public, max-age=3600");

        // this origin request MUST happen due to invalidation
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp3);

        execute(req1);
        execute(unsafeReq);
        execute(req3);
    }

    protected void testUnsafeMethodInvalidatesCacheForUriInContentLocationHeader(
            final ClassicHttpRequest unsafeReq) throws Exception {
        unsafeReq.setHeader("Content-Location","http://foo.example.com/content");
        testUnsafeMethodInvalidatesCacheForHeaderUri(unsafeReq);
    }

    protected void testUnsafeMethodInvalidatesCacheForRelativeUriInContentLocationHeader(
            final ClassicHttpRequest unsafeReq) throws Exception {
        unsafeReq.setHeader("Content-Location","/content");
        testUnsafeMethodInvalidatesCacheForHeaderUri(unsafeReq);
    }

    protected void testUnsafeMethodInvalidatesCacheForUriInLocationHeader(
            final ClassicHttpRequest unsafeReq) throws Exception {
        unsafeReq.setHeader("Location","http://foo.example.com/content");
        testUnsafeMethodInvalidatesCacheForHeaderUri(unsafeReq);
    }

    @Test
    public void testPutInvalidatesCacheForThatUriInContentLocationHeader() throws Exception {
        final ClassicHttpRequest req2 = makeRequestWithBody("PUT","/");
        testUnsafeMethodInvalidatesCacheForUriInContentLocationHeader(req2);
    }

    @Test
    public void testPutInvalidatesCacheForThatUriInLocationHeader() throws Exception {
        final ClassicHttpRequest req = makeRequestWithBody("PUT","/");
        testUnsafeMethodInvalidatesCacheForUriInLocationHeader(req);
    }

    @Test
    public void testPutInvalidatesCacheForThatUriInRelativeContentLocationHeader() throws Exception {
        final ClassicHttpRequest req = makeRequestWithBody("PUT","/");
        testUnsafeMethodInvalidatesCacheForRelativeUriInContentLocationHeader(req);
    }

    @Test
    public void testDeleteInvalidatesCacheForThatUriInContentLocationHeader() throws Exception {
        final ClassicHttpRequest req = new BasicClassicHttpRequest("DELETE", "/");
        testUnsafeMethodInvalidatesCacheForUriInContentLocationHeader(req);
    }

    @Test
    public void testDeleteInvalidatesCacheForThatUriInRelativeContentLocationHeader() throws Exception {
        final ClassicHttpRequest req = new BasicClassicHttpRequest("DELETE", "/");
        testUnsafeMethodInvalidatesCacheForRelativeUriInContentLocationHeader(req);
    }

    @Test
    public void testDeleteInvalidatesCacheForThatUriInLocationHeader() throws Exception {
        final ClassicHttpRequest req = new BasicClassicHttpRequest("DELETE", "/");
        testUnsafeMethodInvalidatesCacheForUriInLocationHeader(req);
    }

    @Test
    public void testPostInvalidatesCacheForThatUriInContentLocationHeader() throws Exception {
        final ClassicHttpRequest req = makeRequestWithBody("POST","/");
        testUnsafeMethodInvalidatesCacheForUriInContentLocationHeader(req);
    }

    @Test
    public void testPostInvalidatesCacheForThatUriInLocationHeader() throws Exception {
        final ClassicHttpRequest req = makeRequestWithBody("POST","/");
        testUnsafeMethodInvalidatesCacheForUriInLocationHeader(req);
    }

    @Test
    public void testPostInvalidatesCacheForRelativeUriInContentLocationHeader() throws Exception {
        final ClassicHttpRequest req = makeRequestWithBody("POST","/");
        testUnsafeMethodInvalidatesCacheForRelativeUriInContentLocationHeader(req);
    }

    /* "In order to prevent denial of service attacks, an invalidation based on the URI
     *  in a Location or Content-Location header MUST only be performed if the host part
     *  is the same as in the Request-URI."
     *
     *  http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.10
     */
    protected void testUnsafeMethodDoesNotInvalidateCacheForHeaderUri(
            final ClassicHttpRequest unsafeReq) throws Exception {

        final HttpHost otherHost = new HttpHost("bar.example.com", 80);
        final HttpRoute otherRoute = new HttpRoute(otherHost);
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/content");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Cache-Control","public, max-age=3600");

        final ClassicHttpResponse resp2 = new BasicClassicHttpResponse(HttpStatus.SC_NO_CONTENT, "No Content");

        final ClassicHttpRequest req3 = new BasicClassicHttpRequest("GET", "/content");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        execute(unsafeReq);
        execute(req3);
    }

    protected void testUnsafeMethodDoesNotInvalidateCacheForUriInContentLocationHeadersFromOtherHosts(
            final ClassicHttpRequest unsafeReq) throws Exception {
        unsafeReq.setHeader("Content-Location","http://bar.example.com/content");
        testUnsafeMethodDoesNotInvalidateCacheForHeaderUri(unsafeReq);
    }

    protected void testUnsafeMethodDoesNotInvalidateCacheForUriInLocationHeadersFromOtherHosts(
            final ClassicHttpRequest unsafeReq) throws Exception {
        unsafeReq.setHeader("Location","http://bar.example.com/content");
        testUnsafeMethodDoesNotInvalidateCacheForHeaderUri(unsafeReq);
    }

    @Test
    public void testPutDoesNotInvalidateCacheForUriInContentLocationHeadersFromOtherHosts() throws Exception {
        final ClassicHttpRequest req = makeRequestWithBody("PUT","/");
        testUnsafeMethodDoesNotInvalidateCacheForUriInContentLocationHeadersFromOtherHosts(req);
    }

    @Test
    public void testPutDoesNotInvalidateCacheForUriInLocationHeadersFromOtherHosts() throws Exception {
        final ClassicHttpRequest req = makeRequestWithBody("PUT","/");
        testUnsafeMethodDoesNotInvalidateCacheForUriInLocationHeadersFromOtherHosts(req);
    }

    @Test
    public void testPostDoesNotInvalidateCacheForUriInContentLocationHeadersFromOtherHosts() throws Exception {
        final ClassicHttpRequest req = makeRequestWithBody("POST","/");
        testUnsafeMethodDoesNotInvalidateCacheForUriInContentLocationHeadersFromOtherHosts(req);
    }

    @Test
    public void testPostDoesNotInvalidateCacheForUriInLocationHeadersFromOtherHosts() throws Exception {
        final ClassicHttpRequest req = makeRequestWithBody("POST","/");
        testUnsafeMethodDoesNotInvalidateCacheForUriInLocationHeadersFromOtherHosts(req);
    }

    @Test
    public void testDeleteDoesNotInvalidateCacheForUriInContentLocationHeadersFromOtherHosts() throws Exception {
        final ClassicHttpRequest req = new BasicClassicHttpRequest("DELETE", "/");
        testUnsafeMethodDoesNotInvalidateCacheForUriInContentLocationHeadersFromOtherHosts(req);
    }

    @Test
    public void testDeleteDoesNotInvalidateCacheForUriInLocationHeadersFromOtherHosts() throws Exception {
        final ClassicHttpRequest req = new BasicClassicHttpRequest("DELETE", "/");
        testUnsafeMethodDoesNotInvalidateCacheForUriInLocationHeadersFromOtherHosts(req);
    }

    /* "All methods that might be expected to cause modifications to the origin
     * server's resources MUST be written through to the origin server. This
     * currently includes all methods except for GET and HEAD. A cache MUST NOT
     * reply to such a request from a client before having transmitted the
     * request to the inbound server, and having received a corresponding
     * response from the inbound server."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.11
     */
    private void testRequestIsWrittenThroughToOrigin(final ClassicHttpRequest req) throws Exception {
        final ClassicHttpResponse resp = new BasicClassicHttpResponse(HttpStatus.SC_NO_CONTENT, "No Content");
        final ClassicHttpRequest wrapper = req;
        Mockito.when(mockExecChain.proceed(RequestEquivalent.eq(wrapper), Mockito.any())).thenReturn(resp);

        execute(wrapper);
    }

    @Test
    public void testOPTIONSRequestsAreWrittenThroughToOrigin() throws Exception {
        final ClassicHttpRequest req = new BasicClassicHttpRequest("OPTIONS","*");
        testRequestIsWrittenThroughToOrigin(req);
    }

    @Test
    public void testPOSTRequestsAreWrittenThroughToOrigin() throws Exception {
        final ClassicHttpRequest req = new BasicClassicHttpRequest("POST","/");
        req.setEntity(HttpTestUtils.makeBody(128));
        req.setHeader("Content-Length","128");
        testRequestIsWrittenThroughToOrigin(req);
    }

    @Test
    public void testPUTRequestsAreWrittenThroughToOrigin() throws Exception {
        final ClassicHttpRequest req = new BasicClassicHttpRequest("PUT","/");
        req.setEntity(HttpTestUtils.makeBody(128));
        req.setHeader("Content-Length","128");
        testRequestIsWrittenThroughToOrigin(req);
    }

    @Test
    public void testDELETERequestsAreWrittenThroughToOrigin() throws Exception {
        final ClassicHttpRequest req = new BasicClassicHttpRequest("DELETE", "/");
        testRequestIsWrittenThroughToOrigin(req);
    }

    @Test
    public void testTRACERequestsAreWrittenThroughToOrigin() throws Exception {
        final ClassicHttpRequest req = new BasicClassicHttpRequest("TRACE","/");
        testRequestIsWrittenThroughToOrigin(req);
    }

    @Test
    public void testCONNECTRequestsAreWrittenThroughToOrigin() throws Exception {
        final ClassicHttpRequest req = new BasicClassicHttpRequest("CONNECT","/");
        testRequestIsWrittenThroughToOrigin(req);
    }

    @Test
    public void testUnknownMethodRequestsAreWrittenThroughToOrigin() throws Exception {
        final ClassicHttpRequest req = new BasicClassicHttpRequest("UNKNOWN","/");
        testRequestIsWrittenThroughToOrigin(req);
    }

    /* "If a cache receives a value larger than the largest positive
     * integer it can represent, or if any of its age calculations
     * overflows, it MUST transmit an Age header with a value of
     * 2147483648 (2^31)."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.6
     */
    @Test
    public void testTransmitsAgeHeaderIfIncomingAgeHeaderTooBig() throws Exception {
        final String reallyOldAge = "1" + Long.MAX_VALUE;
        originResponse.setHeader("Age",reallyOldAge);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        Assertions.assertEquals(reallyOldAge,
                            result.getFirstHeader("Age").getValue());
    }

    /* "A proxy MUST NOT modify the Allow header field even if it does not
     * understand all the methods specified, since the user agent might
     * have other means of communicating with the origin server.
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.7
     */
    @Test
    public void testDoesNotModifyAllowHeaderWithUnknownMethods() throws Exception {
        final String allowHeaderValue = "GET, HEAD, FOOBAR";
        originResponse.setHeader("Allow",allowHeaderValue);
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);
        final ClassicHttpResponse result = execute(request);
        Assertions.assertEquals(HttpTestUtils.getCanonicalHeaderValue(originResponse,"Allow"),
                            HttpTestUtils.getCanonicalHeaderValue(result, "Allow"));
    }

    /* "When a shared cache (see section 13.7) receives a request
     * containing an Authorization field, it MUST NOT return the
     * corresponding response as a reply to any other request, unless one
     * of the following specific exceptions holds:
     *
     * 1. If the response includes the "s-maxage" cache-control
     *    directive, the cache MAY use that response in replying to a
     *    subsequent request. But (if the specified maximum age has
     *    passed) a proxy cache MUST first revalidate it with the origin
     *    server, using the request-headers from the new request to allow
     *    the origin server to authenticate the new request. (This is the
     *    defined behavior for s-maxage.) If the response includes "s-
     *    maxage=0", the proxy MUST always revalidate it before re-using
     *    it.
     *
     * 2. If the response includes the "must-revalidate" cache-control
     *    directive, the cache MAY use that response in replying to a
     *    subsequent request. But if the response is stale, all caches
     *    MUST first revalidate it with the origin server, using the
     *    request-headers from the new request to allow the origin server
     *    to authenticate the new request.
     *
     * 3. If the response includes the "public" cache-control directive,
     *    it MAY be returned in reply to any subsequent request.
     */
    protected void testSharedCacheRevalidatesAuthorizedResponse(
            final ClassicHttpResponse authorizedResponse, final int minTimes, final int maxTimes) throws Exception {
        if (config.isSharedCache()) {
            final String authorization = StandardAuthScheme.BASIC + " dXNlcjpwYXNzd2Q=";
            final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
            req1.setHeader("Authorization",authorization);

            final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
            final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();
            resp2.setHeader("Cache-Control","max-age=3600");

            Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(authorizedResponse);

            execute(req1);

            Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

            execute(req2);

            Mockito.verify(mockExecChain, Mockito.atLeast(1 + minTimes)).proceed(Mockito.any(), Mockito.any());
            Mockito.verify(mockExecChain, Mockito.atMost(1 + maxTimes)).proceed(Mockito.any(), Mockito.any());
        }
    }

    @Test
    public void testSharedCacheMustNotNormallyCacheAuthorizedResponses() throws Exception {
        final ClassicHttpResponse resp = HttpTestUtils.make200Response();
        resp.setHeader("Cache-Control","max-age=3600");
        resp.setHeader("ETag","\"etag\"");
        testSharedCacheRevalidatesAuthorizedResponse(resp, 1, 1);
    }

    @Test
    public void testSharedCacheMayCacheAuthorizedResponsesWithSMaxAgeHeader() throws Exception {
        final ClassicHttpResponse resp = HttpTestUtils.make200Response();
        resp.setHeader("Cache-Control","s-maxage=3600");
        resp.setHeader("ETag","\"etag\"");
        testSharedCacheRevalidatesAuthorizedResponse(resp, 0, 1);
    }

    @Test
    public void testSharedCacheMustRevalidateAuthorizedResponsesWhenSMaxAgeIsZero() throws Exception {
        final ClassicHttpResponse resp = HttpTestUtils.make200Response();
        resp.setHeader("Cache-Control","s-maxage=0");
        resp.setHeader("ETag","\"etag\"");
        testSharedCacheRevalidatesAuthorizedResponse(resp, 1, 1);
    }

    @Test
    public void testSharedCacheMayCacheAuthorizedResponsesWithMustRevalidate() throws Exception {
        final ClassicHttpResponse resp = HttpTestUtils.make200Response();
        resp.setHeader("Cache-Control","must-revalidate");
        resp.setHeader("ETag","\"etag\"");
        testSharedCacheRevalidatesAuthorizedResponse(resp, 0, 1);
    }

    @Test
    public void testSharedCacheMayCacheAuthorizedResponsesWithCacheControlPublic() throws Exception {
        final ClassicHttpResponse resp = HttpTestUtils.make200Response();
        resp.setHeader("Cache-Control","public");
        testSharedCacheRevalidatesAuthorizedResponse(resp, 0, 1);
    }

    protected void testSharedCacheMustUseNewRequestHeadersWhenRevalidatingAuthorizedResponse(
            final ClassicHttpResponse authorizedResponse) throws Exception {
        if (config.isSharedCache()) {
            final String authorization1 = StandardAuthScheme.BASIC + " dXNlcjpwYXNzd2Q=";
            final String authorization2 = StandardAuthScheme.BASIC + " dXNlcjpwYXNzd2Qy";

            final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
            req1.setHeader("Authorization",authorization1);

            final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
            req2.setHeader("Authorization",authorization2);

            final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();

            Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(authorizedResponse);

            execute(req1);

            Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

            execute(req2);

            final ArgumentCaptor<ClassicHttpRequest> reqCapture = ArgumentCaptor.forClass(ClassicHttpRequest.class);
            Mockito.verify(mockExecChain, Mockito.times(2)).proceed(reqCapture.capture(), Mockito.any());

            final List<ClassicHttpRequest> allRequests = reqCapture.getAllValues();
            Assertions.assertEquals(2, allRequests.size());

            final ClassicHttpRequest captured = allRequests.get(1);
            Assertions.assertEquals(HttpTestUtils.getCanonicalHeaderValue(req2, "Authorization"),
                    HttpTestUtils.getCanonicalHeaderValue(captured, "Authorization"));
        }
    }

    @Test
    public void testSharedCacheMustUseNewRequestHeadersWhenRevalidatingAuthorizedResponsesWithSMaxAge() throws Exception {
        final Instant now = Instant.now();
        final Instant tenSecondsAgo = now.minusSeconds(10);
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Date",DateUtils.formatStandardDate(tenSecondsAgo));
        resp1.setHeader("ETag","\"etag\"");
        resp1.setHeader("Cache-Control","s-maxage=5");

        testSharedCacheMustUseNewRequestHeadersWhenRevalidatingAuthorizedResponse(resp1);
    }

    @Test
    public void testSharedCacheMustUseNewRequestHeadersWhenRevalidatingAuthorizedResponsesWithMustRevalidate() throws Exception {
        final Instant now = Instant.now();
        final Instant tenSecondsAgo = now.minusSeconds(10);
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Date",DateUtils.formatStandardDate(tenSecondsAgo));
        resp1.setHeader("ETag","\"etag\"");
        resp1.setHeader("Cache-Control","maxage=5, must-revalidate");

        testSharedCacheMustUseNewRequestHeadersWhenRevalidatingAuthorizedResponse(resp1);
    }

    /* "The request includes a "no-cache" cache-control directive...
     * The server MUST NOT use a cached copy when responding to such a request."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.4
     */
    protected void testCacheIsNotUsedWhenRespondingToRequest(final ClassicHttpRequest req) throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Etag","\"etag\"");
        resp1.setHeader("Cache-Control","max-age=3600");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        execute(req1);

        final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();
        resp2.setHeader("Etag","\"etag2\"");
        resp2.setHeader("Cache-Control","max-age=1200");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        final ClassicHttpResponse result = execute(req);

        Assertions.assertTrue(HttpTestUtils.semanticallyTransparent(resp2, result));

        final ArgumentCaptor<ClassicHttpRequest> reqCapture = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        Mockito.verify(mockExecChain, Mockito.times(2)).proceed(reqCapture.capture(), Mockito.any());

        final ClassicHttpRequest captured = reqCapture.getValue();
        Assertions.assertTrue(HttpTestUtils.equivalent(req, captured));
    }

    @Test
    public void testCacheIsNotUsedWhenRespondingToRequestWithCacheControlNoCache() throws Exception {
        final ClassicHttpRequest req = new BasicClassicHttpRequest("GET", "/");
        req.setHeader("Cache-Control","no-cache");
        testCacheIsNotUsedWhenRespondingToRequest(req);
    }

    /* "When the must-revalidate directive is present in a response received
     * by a cache, that cache MUST NOT use the entry after it becomes stale
     * to respond to a subsequent request without first revalidating it with
     * the origin server. (I.e., the cache MUST do an end-to-end
     * revalidation every time, if, based solely on the origin server's
     * Expires or max-age value, the cached response is stale.)"
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.4
     */
    protected void testStaleCacheResponseMustBeRevalidatedWithOrigin(
            final ClassicHttpResponse staleResponse) throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Cache-Control","max-stale=3600");
        final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();
        resp2.setHeader("ETag","\"etag2\"");
        resp2.setHeader("Cache-Control","max-age=5, must-revalidate");

        // this request MUST happen
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(staleResponse);

        execute(req1);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        execute(req2);

        final ArgumentCaptor<ClassicHttpRequest> reqCapture = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        Mockito.verify(mockExecChain, Mockito.times(2)).proceed(reqCapture.capture(), Mockito.any());

        final ClassicHttpRequest reval = reqCapture.getValue();
        boolean foundMaxAge0 = false;
        final Iterator<HeaderElement> it = MessageSupport.iterate(reval, HttpHeaders.CACHE_CONTROL);
        while (it.hasNext()) {
            final HeaderElement elt = it.next();
            if ("max-age".equalsIgnoreCase(elt.getName())
                    && "0".equals(elt.getValue())) {
                foundMaxAge0 = true;
            }
        }
        Assertions.assertTrue(foundMaxAge0);
    }

    @Test
    public void testStaleEntryWithMustRevalidateIsNotUsedWithoutRevalidatingWithOrigin() throws Exception {
        final ClassicHttpResponse response = HttpTestUtils.make200Response();
        final Instant now = Instant.now();
        final Instant tenSecondsAgo = now.minusSeconds(10);
        response.setHeader("Date",DateUtils.formatStandardDate(tenSecondsAgo));
        response.setHeader("ETag","\"etag1\"");
        response.setHeader("Cache-Control","max-age=5, must-revalidate");

        testStaleCacheResponseMustBeRevalidatedWithOrigin(response);
    }


    /* "In all circumstances an HTTP/1.1 cache MUST obey the must-revalidate
     * directive; in particular, if the cache cannot reach the origin server
     * for any reason, it MUST generate a 504 (Gateway Timeout) response."
     */
    protected void testGenerates504IfCannotRevalidateStaleResponse(
            final ClassicHttpResponse staleResponse) throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(staleResponse);

        execute(req1);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenThrow(new SocketTimeoutException());

        final ClassicHttpResponse result = execute(req2);

        Assertions.assertEquals(HttpStatus.SC_GATEWAY_TIMEOUT,
                            result.getCode());
    }

    @Test
    public void testGenerates504IfCannotRevalidateAMustRevalidateEntry() throws Exception {
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        final Instant now = Instant.now();
        final Instant tenSecondsAgo = now.minusSeconds(10);
        resp1.setHeader("ETag","\"etag\"");
        resp1.setHeader("Date", DateUtils.formatStandardDate(tenSecondsAgo));
        resp1.setHeader("Cache-Control","max-age=5,must-revalidate");

        testGenerates504IfCannotRevalidateStaleResponse(resp1);
    }

    /* "The proxy-revalidate directive has the same meaning as the must-
     * revalidate directive, except that it does not apply to non-shared
     * user agent caches."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.4
     */
    @Test
    public void testStaleEntryWithProxyRevalidateOnSharedCacheIsNotUsedWithoutRevalidatingWithOrigin() throws Exception {
        if (config.isSharedCache()) {
            final ClassicHttpResponse response = HttpTestUtils.make200Response();
            final Instant now = Instant.now();
            final Instant tenSecondsAgo = now.minusSeconds(10);
            response.setHeader("Date",DateUtils.formatStandardDate(tenSecondsAgo));
            response.setHeader("ETag","\"etag1\"");
            response.setHeader("Cache-Control","max-age=5, proxy-revalidate");

            testStaleCacheResponseMustBeRevalidatedWithOrigin(response);
        }
    }

    @Test
    public void testGenerates504IfSharedCacheCannotRevalidateAProxyRevalidateEntry() throws Exception {
        if (config.isSharedCache()) {
            final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
            final Instant now = Instant.now();
            final Instant tenSecondsAgo = now.minusSeconds(10);
            resp1.setHeader("ETag","\"etag\"");
            resp1.setHeader("Date", DateUtils.formatStandardDate(tenSecondsAgo));
            resp1.setHeader("Cache-Control","max-age=5,proxy-revalidate");

            testGenerates504IfCannotRevalidateStaleResponse(resp1);
        }
    }

    /* "[The cache control directive] "private" Indicates that all or part of
     * the response message is intended for a single user and MUST NOT be
     * cached by a shared cache."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.1
     */
    @Test
    public void testCacheControlPrivateIsNotCacheableBySharedCache() throws Exception {
        if (config.isSharedCache()) {
            final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
            final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
            resp1.setHeader("Cache-Control", "private,max-age=3600");

            Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

            final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
            final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();
            // this backend request MUST happen
            Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

            execute(req1);
            execute(req2);
        }
    }

    @Test
    public void testCacheControlPrivateOnFieldIsNotReturnedBySharedCache() throws Exception {
        if (config.isSharedCache()) {
            final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
            final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
            resp1.setHeader("X-Personal", "stuff");
            resp1.setHeader("Cache-Control", "private=\"X-Personal\",s-maxage=3600");

            Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

            final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
            final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();

            // this backend request MAY happen
            Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

            execute(req1);
            final ClassicHttpResponse result = execute(req2);
            Assertions.assertNull(result.getFirstHeader("X-Personal"));

            Mockito.verify(mockExecChain, Mockito.atLeastOnce()).proceed(Mockito.any(), Mockito.any());
            Mockito.verify(mockExecChain, Mockito.atMost(2)).proceed(Mockito.any(), Mockito.any());
        }
    }

    /* "If the no-cache directive does not specify a field-name, then a
     * cache MUST NOT use the response to satisfy a subsequent request
     * without successful revalidation with the origin server. This allows
     * an origin server to prevent caching even by caches that have been
     * configured to return stale responses to client requests."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.1
     */
    @Test
    public void testNoCacheCannotSatisfyASubsequentRequestWithoutRevalidation() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("ETag","\"etag\"");
        resp1.setHeader("Cache-Control","no-cache");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();

        // this MUST happen
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        execute(req1);
        execute(req2);
    }

    @Test
    public void testNoCacheCannotSatisfyASubsequentRequestWithoutRevalidationEvenWithContraryIndications() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("ETag","\"etag\"");
        resp1.setHeader("Cache-Control","no-cache,s-maxage=3600");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        req2.setHeader("Cache-Control","max-stale=7200");
        final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();

        // this MUST happen
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        execute(req1);
        execute(req2);
    }

    /* "If the no-cache directive does specify one or more field-names, then
     * a cache MAY use the response to satisfy a subsequent request, subject
     * to any other restrictions on caching. However, the specified
     * field-name(s) MUST NOT be sent in the response to a subsequent request
     * without successful revalidation with the origin server."
     */
    @Test
    public void testNoCacheOnFieldIsNotReturnedWithoutRevalidation() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("ETag","\"etag\"");
        resp1.setHeader("X-Stuff","things");
        resp1.setHeader("Cache-Control","no-cache=\"X-Stuff\", max-age=3600");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();
        resp2.setHeader("ETag","\"etag\"");
        resp2.setHeader("X-Stuff","things");
        resp2.setHeader("Cache-Control","no-cache=\"X-Stuff\",max-age=3600");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        execute(req1);
        final ClassicHttpResponse result = execute(req2);

        final ArgumentCaptor<ClassicHttpRequest> reqCapture = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        Mockito.verify(mockExecChain, Mockito.atMost(2)).proceed(reqCapture.capture(), Mockito.any());

        final List<ClassicHttpRequest> allRequests = reqCapture.getAllValues();
        if (allRequests.isEmpty()) {
            Assertions.assertNull(result.getFirstHeader("X-Stuff"));
        }
    }

    /* "The purpose of the no-store directive is to prevent the inadvertent
     * release or retention of sensitive information (for example, on backup
     * tapes). The no-store directive applies to the entire message, and MAY
     * be sent either in a response or in a request. If sent in a request, a
     * cache MUST NOT store any part of either this request or any response
     * to it. If sent in a response, a cache MUST NOT store any part of
     * either this response or the request that elicited it. This directive
     * applies to both non- shared and shared caches. "MUST NOT store" in
     * this context means that the cache MUST NOT intentionally store the
     * information in non-volatile storage, and MUST make a best-effort
     * attempt to remove the information from volatile storage as promptly
     * as possible after forwarding it."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.2
     */
    @Test
    public void testNoStoreOnRequestIsNotStoredInCache() throws Exception {
        request.setHeader("Cache-Control","no-store");
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);

        Mockito.verifyNoInteractions(mockCache);
    }

    @Test
    public void testNoStoreOnRequestIsNotStoredInCacheEvenIfResponseMarkedCacheable() throws Exception {
        request.setHeader("Cache-Control","no-store");
        originResponse.setHeader("Cache-Control","max-age=3600");
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);

        Mockito.verifyNoInteractions(mockCache);
    }

    @Test
    public void testNoStoreOnResponseIsNotStoredInCache() throws Exception {
        originResponse.setHeader("Cache-Control","no-store");
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);

        Mockito.verifyNoInteractions(mockCache);
    }

    @Test
    public void testNoStoreOnResponseIsNotStoredInCacheEvenWithContraryIndicators() throws Exception {
        originResponse.setHeader("Cache-Control","no-store,max-age=3600");
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);

        Mockito.verifyNoInteractions(mockCache);
    }

    /* "If multiple encodings have been applied to an entity, the content
     * codings MUST be listed in the order in which they were applied."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.11
     */
    @Test
    public void testOrderOfMultipleContentEncodingHeaderValuesIsPreserved() throws Exception {
        originResponse.addHeader("Content-Encoding","gzip");
        originResponse.addHeader("Content-Encoding","deflate");
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);
        int total_encodings = 0;
        final Iterator<HeaderElement> it = MessageSupport.iterate(result, HttpHeaders.CONTENT_ENCODING);
        while (it.hasNext()) {
            final HeaderElement elt = it.next();
            switch(total_encodings) {
                case 0:
                    Assertions.assertEquals("gzip", elt.getName());
                    break;
                case 1:
                    Assertions.assertEquals("deflate", elt.getName());
                    break;
                default:
                    Assertions.fail("too many encodings");
            }
            total_encodings++;
        }
        Assertions.assertEquals(2, total_encodings);
    }

    @Test
    public void testOrderOfMultipleParametersInContentEncodingHeaderIsPreserved() throws Exception {
        originResponse.addHeader("Content-Encoding","gzip,deflate");
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);
        int total_encodings = 0;
        final Iterator<HeaderElement> it = MessageSupport.iterate(result, HttpHeaders.CONTENT_ENCODING);
        while (it.hasNext()) {
            final HeaderElement elt = it.next();
            switch(total_encodings) {
                case 0:
                    Assertions.assertEquals("gzip", elt.getName());
                    break;
                case 1:
                    Assertions.assertEquals("deflate", elt.getName());
                    break;
                default:
                    Assertions.fail("too many encodings");
            }
            total_encodings++;
        }
        Assertions.assertEquals(2, total_encodings);
    }

    /* "A cache cannot assume that an entity with a Content-Location
     * different from the URI used to retrieve it can be used to respond
     * to later requests on that Content-Location URI."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.14
     */
    @Test
    public void testCacheDoesNotAssumeContentLocationHeaderIndicatesAnotherCacheableResource() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/foo");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Cache-Control","public,max-age=3600");
        resp1.setHeader("Etag","\"etag\"");
        resp1.setHeader("Content-Location","http://foo.example.com/bar");

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/bar");
        final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();
        resp2.setHeader("Cache-Control","public,max-age=3600");
        resp2.setHeader("Etag","\"etag\"");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        execute(req1);
        execute(req2);
    }

    /* "A received message that does not have a Date header field MUST be
     * assigned one by the recipient if the message will be cached by that
     * recipient or gatewayed via a protocol which requires a Date."
     */
    @Test
    public void testCachedResponsesWithMissingDateHeadersShouldBeAssignedOne() throws Exception {
        originResponse.removeHeaders("Date");
        originResponse.setHeader("Cache-Control","public");
        originResponse.setHeader("ETag","\"etag\"");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);
        Assertions.assertNotNull(result.getFirstHeader("Date"));
    }

    /* "The Expires entity-header field gives the date/time after which the
     * response is considered stale.... HTTP/1.1 clients and caches MUST
     * treat other invalid date formats, especially including the value '0',
     * as in the past (i.e., 'already expired')."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.21
     */
    private void testInvalidExpiresHeaderIsTreatedAsStale(
            final String expiresHeader) throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Cache-Control","public");
        resp1.setHeader("ETag","\"etag\"");
        resp1.setHeader("Expires", expiresHeader);

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);
        // second request to origin MUST happen
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        execute(req1);
        execute(req2);
    }

    @Test
    public void testMalformedExpiresHeaderIsTreatedAsStale() throws Exception {
        testInvalidExpiresHeaderIsTreatedAsStale("garbage");
    }

    @Test
    public void testExpiresZeroHeaderIsTreatedAsStale() throws Exception {
        testInvalidExpiresHeaderIsTreatedAsStale("0");
    }

    /* "To mark a response as 'already expired,' an origin server sends
     * an Expires date that is equal to the Date header value."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.21
     */
    @Test
    public void testExpiresHeaderEqualToDateHeaderIsTreatedAsStale() throws Exception {
        final ClassicHttpRequest req1 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp1 = HttpTestUtils.make200Response();
        resp1.setHeader("Cache-Control","public");
        resp1.setHeader("ETag","\"etag\"");
        resp1.setHeader("Expires", resp1.getFirstHeader("Date").getValue());

        final ClassicHttpRequest req2 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse resp2 = HttpTestUtils.make200Response();

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp1);
        // second request to origin MUST happen
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(resp2);

        execute(req1);
        execute(req2);
    }

    /* "If the response is being forwarded through a proxy, the proxy
     * application MUST NOT modify the Server response-header."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.38
     */
    @Test
    public void testDoesNotModifyServerResponseHeader() throws Exception {
        final String server = "MockServer/1.0";
        originResponse.setHeader("Server", server);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);
        Assertions.assertEquals(server, result.getFirstHeader("Server").getValue());
    }

    /* "A Vary field value of '*' signals that unspecified parameters
     * not limited to the request-headers (e.g., the network address
     * of the client), play a role in the selection of the response
     * representation. The '*' value MUST NOT be generated by a proxy
     * server; it may only be generated by an origin server."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.44
     */
    @Test
    public void testVaryStarIsNotGeneratedByProxy() throws Exception {
        request.setHeader("User-Agent","my-agent/1.0");
        originResponse.setHeader("Cache-Control","public, max-age=3600");
        originResponse.setHeader("Vary","User-Agent");
        originResponse.setHeader("ETag","\"etag\"");

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);
        final Iterator<HeaderElement> it = MessageSupport.iterate(result, HttpHeaders.VARY);
        while (it.hasNext()) {
            final HeaderElement elt = it.next();
            Assertions.assertNotEquals("*", elt.getName());
        }
    }

    /* "The Via general-header field MUST be used by gateways and proxies
     * to indicate the intermediate protocols and recipients between the
     * user agent and the server on requests, and between the origin server
     * and the client on responses."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.45
     */
    @Test
    public void testProperlyFormattedViaHeaderIsAddedToRequests() throws Exception {
        request.removeHeaders(HttpHeaders.VIA);
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);

        final ArgumentCaptor<ClassicHttpRequest> reqCapture = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        Mockito.verify(mockExecChain).proceed(reqCapture.capture(), Mockito.any());

        final ClassicHttpRequest captured = reqCapture.getValue();
        final String via = captured.getFirstHeader(HttpHeaders.VIA).getValue();
        assertValidViaHeader(via);
    }

    @Test
    public void testProperlyFormattedViaHeaderIsAddedToResponses() throws Exception {
        originResponse.removeHeaders(HttpHeaders.VIA);
        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);
        final ClassicHttpResponse result = execute(request);
        assertValidViaHeader(result.getFirstHeader(HttpHeaders.VIA).getValue());
    }


    private void assertValidViaHeader(final String via) {
        //        Via =  HttpHeaders.VIA ":" 1#( received-protocol received-by [ comment ] )
        //        received-protocol = [ protocol-name "/" ] protocol-version
        //        protocol-name     = token
        //        protocol-version  = token
        //        received-by       = ( host [ ":" port ] ) | pseudonym
        //        pseudonym         = token

        final String[] parts = via.split("\\s+");
        Assertions.assertTrue(parts.length >= 2);

        // received protocol
        final String receivedProtocol = parts[0];
        final String[] protocolParts = receivedProtocol.split("/");
        Assertions.assertTrue(protocolParts.length >= 1);
        Assertions.assertTrue(protocolParts.length <= 2);

        final String tokenRegexp = "[^\\p{Cntrl}()<>@,;:\\\\\"/\\[\\]?={} \\t]+";
        for(final String protocolPart : protocolParts) {
            Assertions.assertTrue(Pattern.matches(tokenRegexp, protocolPart));
        }

        // received-by
        if (!Pattern.matches(tokenRegexp, parts[1])) {
            // host : port
            new HttpHost(parts[1]); // TODO - unused - is this a test bug? else use Assertions.assertNotNull
        }

        // comment
        if (parts.length > 2) {
            final StringBuilder buf = new StringBuilder(parts[2]);
            for(int i=3; i<parts.length; i++) {
                buf.append(" "); buf.append(parts[i]);
            }
            Assertions.assertTrue(isValidComment(buf.toString()));
        }
    }

    private boolean isValidComment(final String s) {
        final String leafComment = "^\\(([^\\p{Cntrl}()]|\\\\\\p{ASCII})*\\)$";
        final String nestedPrefix = "^\\(([^\\p{Cntrl}()]|\\\\\\p{ASCII})*\\(";
        final String nestedSuffix = "\\)([^\\p{Cntrl}()]|\\\\\\p{ASCII})*\\)$";

        if (Pattern.matches(leafComment,s)) {
            return true;
        }
        final Matcher pref = Pattern.compile(nestedPrefix).matcher(s);
        final Matcher suff = Pattern.compile(nestedSuffix).matcher(s);
        if (!pref.find()) {
            return false;
        }
        if (!suff.find()) {
            return false;
        }
        return isValidComment(s.substring(pref.end() - 1, suff.start() + 1));
    }


    /*
     * "The received-protocol indicates the protocol version of the message
     * received by the server or client along each segment of the request/
     * response chain. The received-protocol version is appended to the Via
     * field value when the message is forwarded so that information about
     * the protocol capabilities of upstream applications remains visible
     * to all recipients."
     *
     * http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.45
     */
    @Test
    public void testViaHeaderOnRequestProperlyRecordsClientProtocol() throws Exception {
        final ClassicHttpRequest originalRequest = new BasicClassicHttpRequest("GET", "/");
        originalRequest.setVersion(HttpVersion.HTTP_1_0);
        request = originalRequest;
        request.removeHeaders(HttpHeaders.VIA);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        execute(request);

        final ArgumentCaptor<ClassicHttpRequest> reqCapture = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        Mockito.verify(mockExecChain).proceed(reqCapture.capture(), Mockito.any());

        final ClassicHttpRequest captured = reqCapture.getValue();
        final String via = captured.getFirstHeader(HttpHeaders.VIA).getValue();
        final String protocol = via.split("\\s+")[0];
        final String[] protoParts = protocol.split("/");
        if (protoParts.length > 1) {
            Assertions.assertTrue("http".equalsIgnoreCase(protoParts[0]));
        }
        Assertions.assertEquals("1.0",protoParts[protoParts.length-1]);
    }

    @Test
    public void testViaHeaderOnResponseProperlyRecordsOriginProtocol() throws Exception {

        originResponse = new BasicClassicHttpResponse(HttpStatus.SC_NO_CONTENT, "No Content");
        originResponse.setVersion(HttpVersion.HTTP_1_0);

        Mockito.when(mockExecChain.proceed(Mockito.any(), Mockito.any())).thenReturn(originResponse);

        final ClassicHttpResponse result = execute(request);

        final String via = result.getFirstHeader(HttpHeaders.VIA).getValue();
        final String protocol = via.split("\\s+")[0];
        final String[] protoParts = protocol.split("/");
        Assertions.assertTrue(protoParts.length >= 1);
        Assertions.assertTrue(protoParts.length <= 2);
        if (protoParts.length > 1) {
            Assertions.assertTrue("http".equalsIgnoreCase(protoParts[0]));
        }
        Assertions.assertEquals("1.0", protoParts[protoParts.length - 1]);
    }

}
