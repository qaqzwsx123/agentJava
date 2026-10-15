

package com.nageoffer.ai.ragent.agent.skill;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.trace.AgentToolBodyTracer;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkill;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 技能加载工具：渐进式披露的入口，技能清单挂在本工具的 description 上，正文由模型判断命中后再取
 */
@RequiredArgsConstructor
public class SkillLoadTool implements AgentTool {

    public static final String TOOL_NAME = "load_skill";
    public static final String DISPLAY_NAME = "加载技能手册";

    /**
     * 加载成功的标记写在结果 metadata 上，遮蔽中间件据此放行该技能解锁的工具
     */
    public static final String LOADED_SKILL_METADATA_KEY = "ragent_loaded_skill";

    private static final String SKILL_CODE_PARAM = "skill_code";

    /**
     * 工具描述头部：告诉模型什么时候该加载技能、什么时候不必加载
     */
    private static final String DESCRIPTION_HEADER = """
            加载一份技能手册。技能是针对具体办事场景写好的操作步骤，用户要办下面某件事时，先加载手册再照着做。
            不看手册就容易办错的工具（如提交、预订），只有加载对应手册后才会出现在可用工具里：直接动手会漏掉必须先问清楚的信息。
            用户只是问规定、要解释说明时不必加载技能，直接用知识库检索回答；当前可用工具里已有的查询工具能直接回答的，也不必加载。

            可用技能：""";

    private final AgentSkillRegistry skillRegistry;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    /**
     * 动态拼接工具描述：固定头部 + 当前所有已启用技能的清单
     */
    @Override
    public String getDescription() {
        // 把每个启用技能格式化成 "- 技能代码（技能名称）：描述"
        String manifest = skillRegistry.listEnabled().stream()
                .map(skill -> "- %s（%s）：%s".formatted(
                        skill.skillCode(), skill.name(), StrUtil.emptyIfNull(skill.description())))
                .collect(Collectors.joining("\n"));
        return DESCRIPTION_HEADER + "\n" + manifest;
    }

    /**
     * 构建工具入参 schema：只需要一个 skill_code 参数，取值限定在当前启用技能代码内
     */
    @Override
    public Map<String, Object> getParameters() {
        // 定义 skill_code 字段：类型为字符串，描述提示模型从可用技能清单里选
        Map<String, Object> skillCode = new LinkedHashMap<>();
        skillCode.put("type", "string");
        skillCode.put("description", "技能标识，取值见本工具说明里的可用技能清单");

        // 如果当前有启用技能，就把它们的代码作为 enum 限制，避免模型乱填
        List<String> codes = skillRegistry.listEnabled().stream().map(AgentSkill::skillCode).toList();
        if (!codes.isEmpty()) {
            skillCode.put("enum", codes);
        }

        // 整个入参是一个 object，只有一个必填字段 skill_code，不允许额外属性
        return Map.of(
                "type", "object",
                "properties", Map.of(SKILL_CODE_PARAM, skillCode),
                "required", List.of(SKILL_CODE_PARAM),
                "additionalProperties", false);
    }

    @Override
    public boolean isReadOnly() {
        // 加载手册不修改任何业务数据，属于只读操作
        return true;
    }

    /**
     * 异步调用入口：用 tracer 包一层，记录工具执行耗时与结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 追踪工具调用体，并把实际执行放到 boundedElastic 线程池，避免阻塞事件循环
        return AgentToolBodyTracer.trace(this, param, () -> Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 正文调用时才从注册表取，改手册无需重建 Agent
     */
    private ToolResultBlock execute(ToolCallParam param) {
        // 参数为空直接失败
        if (param == null) {
            return failure(null, "工具调用参数不能为空");
        }

        // 取出工具调用 ID，用于回填结果
        String toolCallId = param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();

        // 从入参中提取 skill_code，并做 trim
        Object raw = param.getInput() == null ? null : param.getInput().get(SKILL_CODE_PARAM);
        String skillCode = raw instanceof String value ? value.trim() : null;

        // skill_code 不能为空
        if (StrUtil.isBlank(skillCode)) {
            return failure(toolCallId, "工具参数 skill_code 不能为空");
        }

        // 按代码查找技能，找不到或已停用则返回失败
        AgentSkill skill = skillRegistry.findByCode(skillCode);
        if (skill == null) {
            return failure(toolCallId, "技能不存在或已停用：" + skillCode + "，可用技能见本工具说明里的清单");
        }

        // 找到技能，返回成功结果，正文带上手册内容
        return success(toolCallId, skill);
    }

    /**
     * 构造成功结果：返回手册正文，并在 metadata 中写入已加载技能代码
     */
    private ToolResultBlock success(String toolCallId, AgentSkill skill) {
        String text = "已加载技能「" + skill.name() + "」。以下是这件事的办理手册，接下来按它执行：\n\n"
                + StrUtil.emptyIfNull(skill.content());
        // metadata 中的 LOADED_SKILL_METADATA_KEY 会被遮蔽中间件读取，用于解锁该技能下的工具
        return block(toolCallId, text,
                Map.of(LOADED_SKILL_METADATA_KEY, skill.skillCode()), ToolResultState.SUCCESS);
    }

    /**
     * 构造失败结果：只返回错误文本，不带 metadata
     */
    private ToolResultBlock failure(String toolCallId, String text) {
        return block(toolCallId, text, Map.of(), ToolResultState.ERROR);
    }

    /**
     * 统一构造 ToolResultBlock
     */
    private ToolResultBlock block(String toolCallId, String text, Map<String, Object> metadata,
                                  ToolResultState state) {
        // 输出内容是一个文本块
        List<ContentBlock> output = List.of(TextBlock.builder().text(text).build());
        return new ToolResultBlock(toolCallId, TOOL_NAME, output, metadata, state);
    }
}