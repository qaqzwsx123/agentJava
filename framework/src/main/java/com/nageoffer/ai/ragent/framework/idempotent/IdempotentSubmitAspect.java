/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.framework.idempotent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 防止用户重复提交表单信息切面控制器
 */
@Aspect
@Component
@RequiredArgsConstructor
public final class IdempotentSubmitAspect {

    private final RedissonClient redissonClient;
    private final Gson gson = new GsonBuilder()
            .registerTypeHierarchyAdapter(
                    MultipartFile.class,
                    (JsonSerializer<MultipartFile>) (file, type, context) -> {
                        JsonObject json = new JsonObject();
                        json.addProperty("name", file.getName());
                        json.addProperty("originalFilename", file.getOriginalFilename());
                        json.addProperty("contentType", file.getContentType());
                        json.addProperty("size", file.getSize());
                        return json;
                    })
            .create();

    @Value("${ragent.eval.enabled:false}")
    private boolean evalEnabled;

    /**
     * 增强方法标记 {@link IdempotentSubmit} 注解逻辑
     */
    @Around("@annotation(com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit)")
    public Object idempotentSubmit(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 评测开关开启时直接执行原逻辑，便于评测重复调用同一接口；正常模式继续进行防重检查。
        if (evalEnabled) {
            return joinPoint.proceed();
        }
        // 2. 读取防重策略，再按显式业务键或请求特征生成分布式锁键。
        IdempotentSubmit idempotentSubmit = getIdempotentSubmitAnnotation(joinPoint);
        // 获取分布式锁标识
        String lockKey = buildLockKey(joinPoint, idempotentSubmit);
        RLock lock = redissonClient.getLock(lockKey);
        // 尝试获取锁，获取锁失败就意味着已经重复提交，直接抛出异常
        if (!lock.tryLock()) {
            throw new ClientException(idempotentSubmit.message());
        }
        Object result;
        try {
            // 执行标记了防重复提交注解的方法原逻辑
            result = joinPoint.proceed();
        } finally {
            // 3. 原方法无论成功还是失败都释放锁；本实现防并发重入，不在完成后额外保留冷却期。
            lock.unlock();
        }
        return result;
    }

    /**
     * @return 返回自定义防重复提交注解
     */
    public static IdempotentSubmit getIdempotentSubmitAnnotation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        // 根据方法名和参数类型定位目标实现方法，读取实际生效的提交防重注解。
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method targetMethod = joinPoint.getTarget().getClass().getDeclaredMethod(methodSignature.getName(), methodSignature.getMethod().getParameterTypes());
        return targetMethod.getAnnotation(IdempotentSubmit.class);
    }

    /**
     * @return 获取当前线程上下文 ServletPath
     */
    private String getServletPath() {
        // 从当前请求线程读取 Servlet 路径作为默认防重键的一部分；没有请求上下文会直接报错。
        ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return Objects.requireNonNull(sra).getRequest().getServletPath();
    }

    /**
     * @return 当前操作用户 ID
     */
    private String getCurrentUserId() {
        // 把当前用户作为默认防重键的隔离维度，不在此方法重复执行登录校验。
        return UserContext.getUserId();
    }

    /**
     * @return joinPoint md5
     */
    private String calcArgsMD5(ProceedingJoinPoint joinPoint) {
        // 将参数序列化为 JSON 后以 UTF-8 计算摘要；MultipartFile 只序列化预先配置的元信息，不读取文件正文。
        return DigestUtil.md5Hex(gson.toJson(joinPoint.getArgs()).getBytes(StandardCharsets.UTF_8));
    }

    private String buildLockKey(ProceedingJoinPoint joinPoint, IdempotentSubmit idempotentSubmit) {
        // 1. 显式配置的业务键优先，支持通过 SpEL 从参数中取值，适合按业务对象而非整个请求防重。
        if (StrUtil.isNotBlank(idempotentSubmit.key())) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Object keyValue = SpELUtil.parseKey(idempotentSubmit.key(), signature.getMethod(), joinPoint.getArgs());
            return String.format("idempotent-submit:key:%s", keyValue);
        }
        // 2. 未配置业务键时，默认把接口路径、用户 ID 和参数摘要共同作为同一次提交的判据。
        return String.format(
                "idempotent-submit:path:%s:currentUserId:%s:md5:%s",
                getServletPath(),
                getCurrentUserId(),
                calcArgsMD5(joinPoint)
        );
    }
}
