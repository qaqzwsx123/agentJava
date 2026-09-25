
package com.nageoffer.ai.ragent.framework.idempotent;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 防止消息队列消费者重复消费消息切面控制器
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public final class IdempotentConsumeAspect {  //专门用来防止“消息队列（MQ）消费者重复消费消息”的切面

    private final StringRedisTemplate stringRedisTemplate;

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local value = ARGV[1]
            local expire_time_ms = ARGV[2]
            return redis.call('SET', key, value, 'NX', 'GET', 'PX', expire_time_ms)
            """;

    /**
     * 增强方法标记 {@link IdempotentConsume} 注解逻辑
     */
    @Around("@annotation(com.nageoffer.ai.ragent.framework.idempotent.IdempotentConsume)")
    public Object idempotentConsume(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 读取实现方法上的注解，将固定前缀与按入参解析出的业务键拼成消费防重键。
        IdempotentConsume idempotentConsume = getIdempotentConsumeAnnotation(joinPoint);
        String uniqueKey = idempotentConsume.keyPrefix()
                + SpELUtil.parseKey(idempotentConsume.key(), ((MethodSignature) joinPoint.getSignature()).getMethod(), joinPoint.getArgs());
        long keyTimeoutSeconds = idempotentConsume.keyTimeout();

        // 2. Lua 原子执行带 NX/GET/PX 的 SET：首次取得处理权写 CONSUMING，重复请求读回已有状态。
        String absentAndGet = stringRedisTemplate.execute(
                RedisScript.of(LUA_SCRIPT, String.class),
                List.of(uniqueKey),
                IdempotentConsumeStatusEnum.CONSUMING.getCode(),
                String.valueOf(TimeUnit.SECONDS.toMillis(keyTimeoutSeconds))
        );

        // 如果已有消费中状态，提示延迟消费；已完成则直接跳过
        // 3. 已在消费的消息抛异常交给 MQ 延迟重试；已经消费完成的消息不再调用业务方法。
        boolean errorFlag = IdempotentConsumeStatusEnum.isError(absentAndGet);
        if (errorFlag) {
            log.warn("[{}] MQ repeated consumption, wait for delayed retry.", uniqueKey);
            throw new ServiceException(String.format("消息消费者幂等异常，幂等标识：%s", uniqueKey));
        }
        if (IdempotentConsumeStatusEnum.CONSUMED.getCode().equals(absentAndGet)) {
            log.info("[{}] MQ consumption already completed, skip.", uniqueKey);
            return null;
        }

        try {
            // 4. 取得处理权后才执行原消费者；正常返回再写入带过期时间的 CONSUMED 状态。
            Object result = joinPoint.proceed();
            stringRedisTemplate.opsForValue().set(
                    uniqueKey,
                    IdempotentConsumeStatusEnum.CONSUMED.getCode(),
                    keyTimeoutSeconds,
                    TimeUnit.SECONDS
            );
            return result;
        } catch (Throwable ex) {
            // 5. 业务或完成标记写入失败时释放防重键，并把原异常抛回 MQ，让后续投递有机会重试。
            stringRedisTemplate.delete(uniqueKey);
            throw ex;
        }
    }

    /**
     * @return 返回自定义防重复消费注解
     */
    public static IdempotentConsume getIdempotentConsumeAnnotation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        // 先从切点取得方法签名，再到真实目标类查同名同参数方法，读取实现方法上的消费注解。
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method targetMethod = joinPoint.getTarget().getClass().getDeclaredMethod(methodSignature.getName(), methodSignature.getMethod().getParameterTypes());
        return targetMethod.getAnnotation(IdempotentConsume.class);
    }
}
