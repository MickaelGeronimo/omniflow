package com.omniflow.infrastructure.triage.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Diagnostic tool annotation marking inspection capabilities for incident triage.
 * These diagnostic methods are invoked by the IncidentTriageService to collect
 * forensic facts (payload schema, audit trail, ledger balances) for the reasoning engine.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {
    String description() default "";
    String name() default "";
}