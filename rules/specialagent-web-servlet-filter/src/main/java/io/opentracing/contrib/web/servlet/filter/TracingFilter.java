
/*
 * Copyright 2016-2018 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.opentracing.contrib.web.servlet.filter;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.Tracer.SpanBuilder;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Tracing servlet filter.
 *
 * Filter can be programmatically added to {@link ServletContext} or initialized via web.xml.
 *
 * Following code examples show possible initialization:
 *
 * <pre>
 * {@code
  * TracingFilter filter = new TracingFilter(tracer);
 *  servletContext.addFilter("tracingFilter", filter);
  * }
 * </pre>
 *
 * Or include filter in web.xml and:
 * <pre>
 * {@code
 *  GlobalTracer.register(tracer);
 *  servletContext.setAttribute({@link TracingFilter#SPAN_DECORATORS}, listOfDecorators); // optional, if no present ServletFilterSpanDecorator.STANDARD_TAGS is applied
 * }
 * </pre>
 *
 * Current server span context is accessible via {@link HttpServletRequest#getAttribute(String)} with name
 * {@link TracingFilter#SERVER_SPAN_CONTEXT}.
 *
 * @author Pavol Loffay
 */
public class TracingFilter implements Filter {
    private static final Logger log = Logger.getLogger(TracingFilter.class.getName());

    /**
     * Use as a key of {@link ServletContext#setAttribute(String, Object)} to set span decorators
     */
    public static final String SPAN_DECORATORS = TracingFilter.class.getName() + ".spanDecorators";
    /**
     * Use as a key of {@link ServletContext#setAttribute(String, Object)} to skip pattern
     */
    public static final String SKIP_PATTERN = TracingFilter.class.getName() + ".skipPattern";

    /**
     * Used as a key of {@link HttpServletRequest#setAttribute(String, Object)} to inject server span context
     */
    public static final String SERVER_SPAN_CONTEXT = TracingFilter.class.getName() + ".activeSpanContext";

    private FilterConfig filterConfig;

    protected Tracer tracer;
    private List<ServletFilterSpanDecorator> spanDecorators;
    private Pattern skipPattern;

    /**
     * Tracer instance has to be registered with {@link GlobalTracer#register(Tracer)}.
     */
    public TracingFilter() {
        this(GlobalTracer.get());
    }

    /**
     * @param tracer
     */
    public TracingFilter(Tracer tracer) {
        this(tracer, Collections.singletonList(ServletFilterSpanDecorator.STANDARD_TAGS), null);
    }

    /**
     *
     * @param tracer tracer
     * @param spanDecorators decorators
     * @param skipPattern null or pattern to exclude certain paths from tracing e.g. "/health"
     */
    public TracingFilter(Tracer tracer, List<ServletFilterSpanDecorator> spanDecorators, Pattern skipPattern) {
        this.tracer = tracer;
        this.spanDecorators = new ArrayList<>(spanDecorators);
        this.spanDecorators.removeAll(Collections.singleton(null));
        this.skipPattern = skipPattern;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        this.filterConfig = filterConfig;
        ServletContext servletContext = filterConfig.getServletContext();

        // Check whether the servlet context provides a tracer
        Object tracerObj = servletContext.getAttribute(Tracer.class.getName());
        if (tracerObj instanceof Tracer) {
            tracer = (Tracer)tracerObj;
        } else {
            // Add current tracer to servlet context, so available to webapp
            servletContext.setAttribute(Tracer.class.getName(), tracer);
        }

        // use decorators from context attributes
        Object contextAttribute = servletContext.getAttribute(SPAN_DECORATORS);
        if (contextAttribute instanceof Collection) {
            List<ServletFilterSpanDecorator> decorators = new ArrayList<>();
            for (Object decorator: (Collection)contextAttribute) {
                if (decorator instanceof ServletFilterSpanDecorator) {
                    decorators.add((ServletFilterSpanDecorator) decorator);
                } else {
                    log.severe(decorator + " is not an instance of " + ServletFilterSpanDecorator.class);
                }
            }
            this.spanDecorators = decorators.size() > 0 ? decorators : this.spanDecorators;
        }

        contextAttribute = servletContext.getAttribute(SKIP_PATTERN);
        if (contextAttribute instanceof Pattern) {
            skipPattern = (Pattern) contextAttribute;
        }
    }

    private boolean isTraced(HttpServletRequest httpRequest, String headerPrefix,
        boolean drivenByHeaders) {
        if (!drivenByHeaders) {
            return true;
        }
        final Enumeration<String> headerNames = httpRequest.getHeaderNames();

        while (headerNames.hasMoreElements()) {
            final String headerName = headerNames.nextElement().toLowerCase();
            if (headerName.startsWith(headerPrefix)) {
                return true;
            }
        }
        return false;
    }

    private Span buildSpan(HttpServletRequest httpRequest, Span f5Span) {
        SpanContext extractedContext;

        if (f5Span != null) {
            extractedContext = f5Span.context();
        } else {
            extractedContext = tracer.extract(Format.Builtin.HTTP_HEADERS,
                new HttpServletRequestExtractAdapter(httpRequest));
        }

        final Span span = tracer
            .buildSpan(httpRequest.getMethod())
            .asChildOf(extractedContext)
            .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
            .start();

        for (ServletFilterSpanDecorator spanDecorator: spanDecorators) {
            spanDecorator.onRequest(httpRequest, span);
        }

        httpRequest.setAttribute(SERVER_SPAN_CONTEXT, span.context());

        return span;
    }

    private Span buildF5Span(HttpServletRequest httpRequest, String headerPrefix,
        boolean drivenByHeaders) {
        if (!isTraced(httpRequest, headerPrefix, drivenByHeaders)) {
            return null;
        }

        SpanContext extractedContext = tracer.extract(Format.Builtin.HTTP_HEADERS,
            new HttpServletRequestExtractAdapter(httpRequest));

        Map<String, String> tags = new HashMap<>();
        final Enumeration<String> headerNames = httpRequest.getHeaderNames();
        Long ingressTime = null;
        while (headerNames.hasMoreElements()) {
            final String headerName = headerNames.nextElement().toLowerCase();
            if (headerName.startsWith(headerPrefix)) {
                if(headerName.equals(headerPrefix + "ingresstime")) {
                    try {
                        ingressTime = Long.parseLong(httpRequest.getHeader(headerName));
                    } catch (NumberFormatException e) {
                        log.warning("failed to parse header: " + headerName + " value: " + httpRequest.getHeader(headerName));
                        tags.put(headerName.replace(headerPrefix, ""),
                            httpRequest.getHeader(headerName));
                    }
                } else {
                    tags.put(headerName.replace(headerPrefix, ""),
                        httpRequest.getHeader(headerName));
                }
            }
        }

        final SpanBuilder spanBuilder = tracer
            .buildSpan("TransitTime")
            .asChildOf(extractedContext)
            .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER);
        if (ingressTime != null) {
            spanBuilder.withStartTimestamp(ingressTime);
        }
        for (Entry<String, String> entry : tags.entrySet()) {
            spanBuilder.withTag(entry.getKey(), entry.getValue());
        }
        final Span span = spanBuilder.start();


        for (ServletFilterSpanDecorator spanDecorator : spanDecorators) {
            spanDecorator.onRequest(httpRequest, span);
        }
        span.setTag(Tags.COMPONENT, "F5"); // TODO: get via config property?
        span.setTag("ServiceName", "F5"); // TODO: get via config property?

        return span;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;
        HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;

        if (!isTraced(httpRequest, httpResponse)) {
            chain.doFilter(httpRequest, httpResponse);
            return;
        }

        /**
         * If request is traced then do not start new span.
         */
        if (servletRequest.getAttribute(SERVER_SPAN_CONTEXT) != null) {
            chain.doFilter(servletRequest, servletResponse);
        } else {
            String headerPrefix = "F5_".toLowerCase(); // TODO: get via config property (convert to lower case)?
            boolean drivenByHeaders = true; // TODO: get via config property?
            final Span f5Span = buildF5Span(httpRequest, headerPrefix, drivenByHeaders);
            final Span span = buildSpan(httpRequest, f5Span);

            final Boolean[] isAsyncStarted = new Boolean[] {false};
            Long egresstime = null;
            try (Scope scope = tracer.activateSpan(span)) {
                chain.doFilter(servletRequest, servletResponse);
                if (ClassUtil.invoke(isAsyncStarted, httpRequest, ClassUtil.getMethod(httpRequest.getClass(), "isAsyncStarted")) && !isAsyncStarted[0]) {
                    for (ServletFilterSpanDecorator spanDecorator : spanDecorators) {
                        spanDecorator.onResponse(httpRequest, httpResponse, span);
                    }
                    if (f5Span != null) {
                        for (String headerName : httpResponse.getHeaderNames()) {
                            headerName = headerName.toLowerCase();
                            if (headerName.startsWith(headerPrefix)) {
                                if (headerName.equals(headerPrefix + "egresstime")) {
                                    try {
                                        egresstime = Long
                                            .parseLong(httpResponse.getHeader(headerName));
                                    } catch (NumberFormatException e) {
                                        log.warning(
                                            "failed to parse header: " + headerName + " value: "
                                                + httpRequest.getHeader(headerName));
                                        f5Span.setTag(headerName.replace(headerPrefix, ""),
                                            httpResponse.getHeader(headerName));
                                    }
                                } else {
                                    f5Span.setTag(headerName.replace(headerPrefix, ""),
                                        httpResponse.getHeader(headerName));
                                }
                            }
                        }
                    }
                }
            // catch all exceptions (e.g. RuntimeException, ServletException...)
            } catch (Throwable ex) {
                for (ServletFilterSpanDecorator spanDecorator : spanDecorators) {
                    spanDecorator.onError(httpRequest, httpResponse, ex, span);
                }
                throw ex;
            } finally {
                if (isAsyncStarted[0]) {
                    // what if async is already finished? This would not be called
                    httpRequest.getAsyncContext()
                            .addListener(new TracingAsyncListener(span, spanDecorators));
                } else {
                    // If not async, then need to explicitly finish the span associated with the scope.
                    // This is necessary, as we don't know whether this request is being handled
                    // asynchronously until after the scope has already been started.
                    span.finish();
                }
                if (f5Span != null) {
                    if (egresstime != null) {
                        f5Span.finish(egresstime);
                    } else {
                        f5Span.finish();
                    }
                }
            }
        }
    }

    @Override
    public void destroy() {
        this.filterConfig = null;
    }

    /**
     * It checks whether a request should be traced or not.
     *
     * @param httpServletRequest request
     * @param httpServletResponse response
     * @return whether request should be traced or not
     */
    protected boolean isTraced(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        // skip URLs matching skip pattern
        // e.g. pattern is defined as '/health|/status' then URL 'http://localhost:5000/context/health' won't be traced
        if (skipPattern != null) {
            String url = httpServletRequest.getRequestURI().substring(httpServletRequest.getContextPath().length());
            return !skipPattern.matcher(url).matches();
        }

        return true;
    }

    /**
     * Get context of server span.
     *
     * @param servletRequest request
     * @return server span context
     */
    public static SpanContext serverSpanContext(ServletRequest servletRequest) {
        return (SpanContext) servletRequest.getAttribute(SERVER_SPAN_CONTEXT);
    }
}
