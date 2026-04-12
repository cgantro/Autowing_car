package com.project.global.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
@Aspect
@Component
public class LoggingAspect {

    @Value("${app.logging.slow-threshold-ms:500}")
    private long slowThresholdMs;

    @Pointcut("within(com.project..*Controller)")
    public void controllerMethods() {
    }

    @Pointcut("within(com.project.domain..*Service) || within(com.project.application..*Service)")
    public void coreServiceMethods() {
    }

    @Around("controllerMethods() || coreServiceMethods()")
    public Object logMethodExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().toShortString();
        String args = Arrays.stream(joinPoint.getArgs()).map(this::maskSensitive).collect(Collectors.joining(", "));

        log.debug("▶ [START] {} | Args: [{}]", methodName, args);

        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;
            if (duration >= slowThresholdMs) {
                log.warn("🐢 [SLOW] {} | Time: {}ms", methodName, duration);
            } else {
                log.debug("◀ [END] {} | Time: {}ms", methodName, duration);
            }
            return result;
        } catch (Throwable e) {
            long duration = System.currentTimeMillis() - start;
            log.error("⚠ [ERROR] {} | Time: {}ms | Exception: {}", methodName, duration, e.getMessage());
            throw e;
        }
    }

    private String maskSensitive(Object arg) {
        if (arg == null) {
            return "null";
        }
        String text = String.valueOf(arg);
        if (text.length() > 300) {
            return "<payload:" + text.length() + " chars>";
        }
        return text
                .replaceAll("(?i)password\\s*[=:]\\s*[^,} ]+", "password=***")
                .replaceAll("(?i)token\\s*[=:]\\s*[^,} ]+", "token=***");
    }
}
