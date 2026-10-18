
package com.nageoffer.ai.ragent.framework.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等注解，防止用户重复提交表单信息
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentSubmit { //防重复提交注解，标记哪些方法需要防重，可以指定判断重复的 Key 和提示信息

    /**
     * 通过 SpEL 表达式生成的唯一 Key，优先级高于默认幂等逻辑
     */
    String key() default "";

    /**
     * 触发幂等失败逻辑时，返回的错误提示信息
     */
    String message() default "您操作太快，请稍后再试";
}
