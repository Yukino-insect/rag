package com.jh.han.rag.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 仅在 {@code rag.engine.type=agent} 时装配 Agent 运行时。 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(prefix = "rag.engine", name = "type", havingValue = "agent")
public @interface ConditionalOnAgentEngine {
}
