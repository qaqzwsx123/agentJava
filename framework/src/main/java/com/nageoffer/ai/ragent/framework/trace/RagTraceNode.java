
package com.nageoffer.ai.ragent.framework.trace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 RAG 链路中的普通节点
 */
@Target(ElementType.METHOD) //只能标在方法上
@Retention(RetentionPolicy.RUNTIME) //注解在运行时保留
public @interface RagTraceNode { //追踪注解。标记需要记录的方法，配置节点名称和类型，由项目中的 RagTraceAspect 切面记录执行情况

    /**
     * 节点名称（用于展示）
     */
    String name() default "";

    /**
     * 节点类型（用于分组统计）
     */
    String type() default "METHOD";
}
