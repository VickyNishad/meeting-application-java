package com.example.meetplatform.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Exposes the current OpenTelemetry trace id so it can be returned in errors and stored in audit logs. */
@Component
public class TraceContext {

    private final ObjectProvider<Tracer> tracer;

    public TraceContext(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    public String currentTraceId() {
        Tracer t = tracer.getIfAvailable();
        if (t == null) {
            return null;
        }
        Span span = t.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
