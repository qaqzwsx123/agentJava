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

package com.nageoffer.ai.ragent.framework.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.AbstractException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Optional;

/**
 * 全局异常处理器
 * 拦截指定异常并通过优雅构建方式返回前端信息
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private String maxFileSize;

    @Value("${spring.servlet.multipart.max-request-size:100MB}")
    private String maxRequestSize;

    /**
     * 拦截参数验证异常
     */
    @SneakyThrows
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public Result<Void> validExceptionHandler(HttpServletRequest request, MethodArgumentNotValidException ex) {
        // 1. 从绑定结果中取第一个字段校验错误，使用它的默认文案，不把整份校验对象返回前端。
        BindingResult bindingResult = ex.getBindingResult();
        FieldError firstFieldError = CollectionUtil.getFirst(bindingResult.getFieldErrors());
        String exceptionStr = Optional.ofNullable(firstFieldError)
                .map(FieldError::getDefaultMessage)
                .orElse(StrUtil.EMPTY);
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), exceptionStr);
        // 2. 对外归入客户端参数错误，只保留一条清晰提示，服务端日志记录请求位置。
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), exceptionStr);
    }

    /**
     * 拦截方法参数校验异常
     * 不接住会落到兜底分支，前端只能看到一句「系统执行出错」
     */
    @ExceptionHandler(value = HandlerMethodValidationException.class)
    public Result<Void> methodValidationExceptionHandler(HttpServletRequest request,
                                                        HandlerMethodValidationException ex) {
        // 1. 展开各方法参数的可解析错误，选择第一条非空提示；没有具体提示时使用通用参数错误文案。
        String message = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse("请求参数不合法");
        log.warn("[{}] {} [ex] {}", request.getMethod(), getUrl(request), message);
        // 将已经选出的参数或上传限制文案包装为统一客户端错误响应。
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), message);
    }

    /**
     * 拦截应用内抛出的异常
     */
    @ExceptionHandler(value = {AbstractException.class})
    public Result<Void> abstractException(HttpServletRequest request, AbstractException ex) {
        // 1. 项目业务异常自带错误码和文案；若有底层原因，日志保留原因堆栈，响应仍使用业务层约定。
        if (ex.getCause() != null) {
            log.error("[{}] {} [ex] {}", request.getMethod(), request.getRequestURL().toString(), ex, ex.getCause());
            return Results.failure(ex);
        }
        // 2. 没有底层原因的业务异常只展开最多五层调用栈，控制日志体量，同时保留错误发生位置。
        StringBuilder stackTraceBuilder = new StringBuilder();
        stackTraceBuilder.append(ex.getClass().getName()).append(": ").append(ex.getErrorMessage()).append("\n");
        StackTraceElement[] stackTrace = ex.getStackTrace();
        for (int i = 0; i < Math.min(5, stackTrace.length); i++) {
            stackTraceBuilder.append("\tat ").append(stackTrace[i]).append("\n");
        }
        log.error("[{}] {} [ex] {} \n\n{}", request.getMethod(), request.getRequestURL().toString(), ex, stackTraceBuilder);
        return Results.failure(ex);
    }

    /**
     * 拦截未登录异常
     */
    @ExceptionHandler(value = NotLoginException.class)
    public Result<Void> notLoginException(HttpServletRequest request, NotLoginException ex) {
        // 记录认证失败位置，并返回固定的未登录或过期提示，不直接把认证框架的内部消息交给前端。
        log.warn("[{}] {} [auth] not-login: {}", request.getMethod(), getUrl(request), ex.getMessage());
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), "未登录或登录已过期");
    }

    /**
     * 拦截无角色权限异常
     */
    @ExceptionHandler(value = NotRoleException.class)
    public Result<Void> notRoleException(HttpServletRequest request, NotRoleException ex) {
        // 无角色权限统一按客户端错误返回，具体角色信息仅通过日志排查。
        log.warn("[{}] {} [auth] no-role: {}", request.getMethod(), getUrl(request), ex.getMessage());
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), "权限不足");
    }

    /**
     * 拦截文件上传大小超限异常
     */
    @ExceptionHandler(value = MaxUploadSizeExceededException.class)
    public Result<Void> maxUploadSizeExceededException(HttpServletRequest request, MaxUploadSizeExceededException ex) {
        // 1. 先记录请求位置；根据异常原因链区分单文件超限与整个请求超限，使用相应配置生成提示。
        log.warn("[{}] {} [upload] 文件上传大小超限: {}", request.getMethod(), getUrl(request), ex.getMessage());
        String message;
        if (ex.getCause() instanceof IllegalStateException
                && ex.getCause().getCause() instanceof FileSizeLimitExceededException) {
            message = "上传文件大小超过限制，单个文件最大允许 " + maxFileSize;
        } else {
            message = "上传请求大小超过限制，单次请求最大允许 " + maxRequestSize;
        }
        // 将已经选出的参数或上传限制文案包装为统一客户端错误响应。
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), message);
    }

    /**
     * 拦截未捕获异常
     */
    @ExceptionHandler(value = Throwable.class)
    public Result<Void> defaultErrorHandler(HttpServletRequest request, Throwable throwable) {
        // 未被具体处理器接住的错误完整记录在服务端，对外只返回默认系统错误码和文案。
        log.error("[{}] {} ", request.getMethod(), getUrl(request), throwable);
        return Results.failure();
    }

    private String getUrl(HttpServletRequest request) {
        // 无查询串时返回请求 URL；有查询串时拼接问号和原查询参数，供异常日志定位请求。
        if (StrUtil.isBlank(request.getQueryString())) {
            return request.getRequestURL().toString();
        }
        return request.getRequestURL().toString() + "?" + request.getQueryString();
    }
}
