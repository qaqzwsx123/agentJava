
package com.nageoffer.ai.ragent.framework.distributedid;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import org.springframework.stereotype.Component;

/**
 * 自定义 ID 生成器
 * 基于 Hutool 的 Snowflake 算法，替换 MyBatisPlus 默认的分布式 ID 生成策略
 */
@Component
public class  CustomIdentifierGenerator implements IdentifierGenerator {

    @Override
    public Number nextId(Object entity) {
        // 委托 Hutool 当前 Snowflake 实例生成数值 ID，实体参数不参与编号计算。
        return IdUtil.getSnowflakeNextId();
    }

    @Override
    public String nextUUID(Object entity) {
        // 这里返回雪花 ID 的字符串形式，供需要字符串主键的映射使用，并非随机 UUID。
        return IdUtil.getSnowflakeNextIdStr();
    }
}
