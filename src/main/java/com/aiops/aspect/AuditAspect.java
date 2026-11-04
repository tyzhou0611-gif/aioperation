package com.aiops.aspect;

import com.aiops.entity.AuditLog;
import com.aiops.repository.AuditLogRepository;
import javax.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

@Aspect @Component
public class AuditAspect {

    private final AuditLogRepository auditLogRepository;

    public AuditAspect(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Around("execution(* com.aiops.controller.*.*(..)) && !execution(* com.aiops.controller.AuthController.*(..))")
    public Object audit(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = pjp.proceed();
        long duration = System.currentTimeMillis() - start;

        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest req = attrs.getRequest();
                String username = req.getRemoteUser() != null ? req.getRemoteUser() : "anonymous";
                String params = Arrays.toString(pjp.getArgs());
                if (params.length() > 500) params = params.substring(0, 500);
                // 脱敏：替换密码字段
                params = params.replaceAll("password=[^,&]+", "password=***");

                AuditLog log = new AuditLog();
                log.setUsername(username);
                log.setIpAddress(req.getRemoteAddr());
                log.setRequestUrl(req.getRequestURI());
                log.setHttpMethod(req.getMethod());
                log.setRequestParams(params);
                log.setDurationMs(duration);
                auditLogRepository.save(log);
            }
        } catch (Exception e) {
            // 审计失败不影响主流程
        }
        return result;
    }
}