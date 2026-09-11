package com.omniflow.infrastructure.ai.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation marking an autonomous agent tool capability.
 * Enables deterministic function registration and LLM execution.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {
    String description() default "";
    String name() default "";
}
