/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.annotation.ConsumesJson;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class FallbackServiceAllowHeaderTest {

    @Nullable
    private static volatile HttpStatus deferredStatus;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService(new Object() {
                @Get("/annotated")
                public String get() {
                    return "get";
                }

                @Delete("/annotated")
                public String delete() {
                    return "delete";
                }

                @Get("/media-type")
                @ProducesJson
                public String getJson() {
                    return "{}";
                }

                @Post("/media-type")
                @ConsumesJson
                public String postJson() {
                    return "{}";
                }
            });
            sb.route().get("/get").build((ctx, req) -> HttpResponse.of(200));
            sb.route().post("/post").build((ctx, req) -> HttpResponse.of(200));
            // A trailing slash route adds a fallback route for '/slash' which serves GET with a redirect.
            sb.route().get("/slash/").build((ctx, req) -> HttpResponse.of(200));
            sb.route().post("/slash").build((ctx, req) -> HttpResponse.of(200));
        }
    };

    @RegisterExtension
    static final ServerExtension customErrorHandlerServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.route().get("/get").build((ctx, req) -> HttpResponse.of(200));
            sb.route().get("/hidden").build((ctx, req) -> HttpResponse.of(200));
            sb.route().post("/consumes").consumes(MediaType.JSON)
              .build((ctx, req) -> HttpResponse.of(200));
            sb.errorHandler(new ServerErrorHandler() {
                @Nullable
                @Override
                public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
                    return null;
                }

                @Override
                public AggregatedHttpResponse renderStatus(@Nullable ServiceRequestContext ctx,
                                                           ServiceConfig config,
                                                           @Nullable RequestHeaders headers,
                                                           HttpStatus status,
                                                           @Nullable String description,
                                                           @Nullable Throwable cause) {
                    assert ctx != null;
                    final HttpStatusException deferred = ctx.routingContext().deferredStatusException();
                    deferredStatus = deferred != null ? deferred.httpStatus() : null;
                    final HttpStatus renderedStatus =
                            "/hidden".equals(ctx.path()) ? HttpStatus.NOT_FOUND : status;
                    return AggregatedHttpResponse.of(
                            ResponseHeaders.of(renderedStatus,
                                               HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8),
                            HttpData.ofUtf8("custom " + renderedStatus.code()));
                }
            });
        }
    };

    @BeforeEach
    void resetDeferredStatus() {
        deferredStatus = null;
    }

    @Test
    void annotatedService() {
        final AggregatedHttpResponse res = BlockingWebClient.of(server.httpUri()).post("/annotated", "");
        assertThat(res.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.headers().get(HttpHeaderNames.ALLOW)).isEqualTo("GET,HEAD,DELETE");
    }

    @Test
    void route() {
        final AggregatedHttpResponse res = BlockingWebClient.of(server.httpUri()).post("/get", "");
        assertThat(res.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.headers().get(HttpHeaderNames.ALLOW)).isEqualTo("GET,HEAD");
    }

    @Test
    void headIsNotAllowedWithoutGet() {
        final AggregatedHttpResponse res = BlockingWebClient.of(server.httpUri()).get("/post");
        assertThat(res.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.headers().get(HttpHeaderNames.ALLOW)).isEqualTo("POST");
    }

    @Test
    void headRequest() {
        final AggregatedHttpResponse res =
                BlockingWebClient.of(server.httpUri()).execute(HttpRequest.of(HttpMethod.HEAD, "/post"));
        assertThat(res.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.headers().get(HttpHeaderNames.ALLOW)).isEqualTo("POST");
    }

    @Test
    void mediaTypesAreIgnored() {
        final AggregatedHttpResponse res = BlockingWebClient.of(server.httpUri()).execute(
                RequestHeaders.builder(HttpMethod.PUT, "/media-type")
                              .contentType(MediaType.PLAIN_TEXT_UTF_8)
                              .add(HttpHeaderNames.ACCEPT, MediaType.PLAIN_TEXT_UTF_8.toString())
                              .build(),
                "text");
        assertThat(res.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.headers().get(HttpHeaderNames.ALLOW)).isEqualTo("GET,HEAD,POST");
    }

    @Test
    void fallbackRouteForTrailingSlashIsExcluded() {
        final AggregatedHttpResponse res =
                BlockingWebClient.of(server.httpUri()).execute(HttpRequest.of(HttpMethod.DELETE, "/slash"));
        assertThat(res.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.headers().get(HttpHeaderNames.ALLOW)).isEqualTo("POST");
    }

    @Test
    void notFoundHasNoAllowHeader() {
        final AggregatedHttpResponse res = BlockingWebClient.of(server.httpUri()).post("/not-found", "");
        assertThat(res.status()).isSameAs(HttpStatus.NOT_FOUND);
        assertThat(res.headers().contains(HttpHeaderNames.ALLOW)).isFalse();
    }

    @Test
    void customErrorHandler() {
        final AggregatedHttpResponse res =
                BlockingWebClient.of(customErrorHandlerServer.httpUri()).post("/get", "");
        assertThat(res.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.contentUtf8()).isEqualTo("custom 405");
        assertThat(res.headers().get(HttpHeaderNames.ALLOW)).isEqualTo("GET,HEAD");
    }

    @Test
    void customErrorHandlerRenderingOtherStatus() {
        final AggregatedHttpResponse res =
                BlockingWebClient.of(customErrorHandlerServer.httpUri()).post("/hidden", "");
        assertThat(res.status()).isSameAs(HttpStatus.NOT_FOUND);
        assertThat(res.headers().contains(HttpHeaderNames.ALLOW)).isFalse();
    }

    @Test
    void deferredStatusIsNotOverwritten() {
        final AggregatedHttpResponse res = BlockingWebClient.of(customErrorHandlerServer.httpUri()).execute(
                RequestHeaders.builder(HttpMethod.PUT, "/consumes")
                              .contentType(MediaType.PLAIN_TEXT_UTF_8)
                              .build(),
                "text");
        assertThat(res.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.headers().get(HttpHeaderNames.ALLOW)).isEqualTo("POST");
        assertThat(deferredStatus).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
