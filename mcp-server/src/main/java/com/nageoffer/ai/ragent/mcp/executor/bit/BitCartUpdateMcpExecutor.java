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

package com.nageoffer.ai.ragent.mcp.executor.bit;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.mcp.dao.entity.CartDO;
import com.nageoffer.ai.ragent.mcp.dao.entity.ProductSkuDO;
import com.nageoffer.ai.ragent.mcp.dao.mapper.CartMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.ProductSkuMapper;
import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
import com.nageoffer.ai.ragent.mcp.executor.McpToolResults;
import com.nageoffer.ai.ragent.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.integer;

/**
 * 设置购物车里某个 SKU 的目标数量，0 即移除
 * <p>
 * 定成「设为几件」而不是「加几件」：加购语义重发一次就翻倍，设置语义发多少次结果都一样
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitCartUpdateMcpExecutor {

    private static final String TOOL_ID = "set_cart_item";

    private static final int MAX_QUANTITY = 99;

    private final CartMapper cartMapper;
    private final ProductSkuMapper productSkuMapper;

    @Bean
    public McpServerFeatures.SyncToolSpecification setCartItemToolSpecification() {
        // 把工具定义和请求处理函数绑定为同步调用规格，交由 Spring 收集并注册到 MCP 服务。
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        // 先声明参数类型、必填项及展示说明，供模型发现工具时理解输入；实际调用仍会执行下面的业务校验。
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("skuCode", "商品 SKU 型号，如 BIT-A18，来自商品查询或购物车查询")
                        .title("商品型号"))
                .required(integer("quantity", "购物车里这件商品最终要有几件，填 0 表示从购物车移除。"
                        + "注意是最终数量而不是增量：车里已有 1 件、用户说再加 1 件，这里填 2")
                        .title("目标数量"))
                .build();

        // 显式声明为写操作，客户端可据此识别可能改变业务状态的工具。
        return Tool.builder()
                .name(TOOL_ID)
                .description("设置当前登录用户购物车中某个商品的数量，填 0 即移除。"
                        + "数量是目标值不是增量，用户说「再加两件」时要先查购物车拿到现有数量再相加。"
                        + "已下架商品无法加入；缺货商品可以留在车里，但下单时会被拦下")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.WRITE)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        // 把数量解释为目标数量并校验范围：0 表示移除，正数则查询商品后新增或更新购物车行。
        long startMs = System.currentTimeMillis();
        String userId = McpToolResults.userId(request);
        if (userId == null) {
            return McpToolResults.identityRequired(TOOL_ID);
        }
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String skuCode = StrUtil.trimToNull(MapUtil.getStr(args, "skuCode"));
            Integer quantity = MapUtil.getInt(args, "quantity");

            if (skuCode == null) {
                return BitToolSupport.rejected("请先确认要调整哪一件商品，型号可用商品查询或购物车查询取得");
            }
            if (quantity == null || quantity < 0) {
                return BitToolSupport.rejected("目标数量缺失或不合法，要一个 0 到 " + MAX_QUANTITY + " 之间的整数");
            }
            if (quantity > MAX_QUANTITY) {
                return BitToolSupport.rejected("单件商品一次最多买 " + MAX_QUANTITY + " 件，请向用户确认数量");
            }

            String result;
            // 目标数量为零时直接执行移除；新增与修改路径才需要检查商品信息。
            if (quantity == 0) {
                result = remove(userId, skuCode);
            } else {
                ProductSkuDO product = loadProduct(skuCode);
                if (product == null) {
                    return BitToolSupport.rejected("未找到商品 " + skuCode + "，请确认型号");
                }
                if (!"在售".equals(product.getStatus())) {
                    return BitToolSupport.rejected(String.format(
                            "%s（%s）已下架，无法加入购物车，可以帮用户看看同品类还在售的型号",
                            product.getName(), product.getSkuCode()));
                }
                result = upsert(userId, product, quantity);
            }

            log.info("MCP 工具调用完成, toolId={}, skuCode={}, quantity={}, elapsed={}ms",
                    TOOL_ID, skuCode, quantity, System.currentTimeMillis() - startMs);
            return McpToolResults.success(result);
        } catch (Exception e) {
            // 异常详情留在服务端日志中，向模型返回统一的工具失败说明。
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_ID, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("购物车调整", e);
        }
    }

    /**
     * 本来就不在车里也算成功：目标状态是「车里没有它」，这一条本就是幂等的
     */
    private String remove(String userId, String skuCode) {
        // 删除当前用户与指定 SKU 对应的购物车行；即使本来不存在，也按已移除返回，便于重复操作。
        int removed = cartMapper.delete(Wrappers.<CartDO>lambdaQuery()
                .eq(CartDO::getUserId, userId).eq(CartDO::getSkuCode, skuCode));
        return removed > 0
                ? String.format("已从购物车移除 %s", skuCode)
                : String.format("购物车里本来就没有 %s，当前状态与目标一致", skuCode);
    }

    private ProductSkuDO loadProduct(String skuCode) {
        // 按 SKU 读取商品详情，不存在时返回 null；是否在售由 handleCall 检查，这里不扣库存。
        return productSkuMapper.selectOne(Wrappers.<ProductSkuDO>lambdaQuery()
                .eq(ProductSkuDO::getSkuCode, skuCode));
    }

    private String upsert(String userId, ProductSkuDO product, int quantity) {
        // 保存目标数量并展示当前价格；更新已有行时保留加入时价格，库存不够只提醒，不预占库存。
        CartDO existing = cartMapper.selectOne(Wrappers.<CartDO>lambdaQuery()
                .eq(CartDO::getUserId, userId).eq(CartDO::getSkuCode, product.getSkuCode()));
        Integer before = existing == null ? null : existing.getQuantity();
        cartMapper.upsert(userId, product.getSkuCode(), quantity, product.getPrice());

        StringBuilder sb = new StringBuilder();
        sb.append(before == null
                ? String.format("已加入购物车: %s（%s）×%d%n", product.getName(), product.getSkuCode(), quantity)
                : String.format("已调整购物车数量: %s（%s）%d 件 → %d 件%n",
                product.getName(), product.getSkuCode(), before, quantity));
        sb.append(String.format("单价 %s，小计 %s%n", BitToolSupport.money(product.getPrice()),
                BitToolSupport.money(product.getPrice().multiply(BigDecimal.valueOf(quantity)))));
        if (product.getStock() <= 0) {
            sb.append("提示: 这件商品当前缺货，可以先放在车里，但现在下单会被拦下\n");
        } else if (product.getStock() < quantity) {
            sb.append(String.format("提示: 这件商品当前只剩 %d 件，下单时最多买这么多%n", product.getStock()));
        }
        return sb.toString().trim();
    }

}
