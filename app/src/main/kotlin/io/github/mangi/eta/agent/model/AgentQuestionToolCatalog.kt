package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * ask_user 的无设置工具合同：主代理在「关键参数确实缺失且无法从上下文推断」时，单独成批向用户提一个问题。
 *
 * 这里只声明模型可见的 Schema；解析与受理边界在 [io.github.mangi.eta.agent.question.AgentQuestionCodec]。
 * 该工具不依赖任何用户开关，默认随主目录发布；子代理白名单会把它排除，子代理只能把缺信息报告给主代理。
 */
internal object AgentQuestionToolCatalog {
    const val NAME = "ask_user"

    fun appendTo(tools: JSONArray) {
        val option = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put("id", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 64)
                        .put("description", "稳定选项 ID，回答按它回填；不要用展示文本代替。"))
                    .put("label", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 200)
                        .put("description", "给用户看的短标签。"))
                    .put("description", JSONObject().put("type", "string").put("maxLength", 1000)
                        .put("description", "可选的补充说明，解释该选项的含义或后果。")),
            )
            .put("required", JSONArray().put("id").put("label"))

        tools.put(
            AgentToolSchema.function(
                name = NAME,
                description =
                    "向用户提一个会影响执行结果的关键问题，并等待回答。只在关键参数确实缺失、" +
                        "且无法从当前上下文合理推断时使用；能从上下文确定或可靠默认的细节直接处理，不要为确认细节而提问。" +
                        "必须单独成批调用：同一批工具调用里不要混入其它工具，只发一个 ask_user。" +
                        "提供 2-8 个互不重叠且可执行的选项；recommended_option_id 只是参考提示，" +
                        "不要替用户预先决定，也不要暗示用户只能选它。" +
                        "用户以 option 明确选择、以 other 自定义、以 delegate 暂时交给后续处理；" +
                        "allow_other/allow_delegation/allow_note 未给出的能力不要索取。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put("additionalProperties", false)
                    .put(
                        "properties",
                        JSONObject()
                            .put("title", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 200)
                                .put("description", "问题标题，简洁说明要决定什么。"))
                            .put("question", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 4000)
                                .put("description", "完整问题，包含做出选择所需的背景与约束。"))
                            .put(
                                "options",
                                JSONObject()
                                    .put("type", "array")
                                    .put("minItems", 2)
                                    .put("maxItems", 8)
                                    .put("items", option)
                                    .put("description", "2-8 个候选选项；id 必须唯一且稳定。"),
                            )
                            .put("recommended_option_id", JSONObject().put("type", "string").put("maxLength", 64)
                                .put("description", "可选；给定时必须是某个选项的 id，仅作参考提示，不自动选择。"))
                            .put("allow_other", JSONObject().put("type", "boolean")
                                .put("description", "是否允许用户自定义回答；默认 true。"))
                            .put("allow_delegation", JSONObject().put("type", "boolean")
                                .put("description", "是否允许用户把问题暂时交给后续处理；默认 true。"))
                            .put("allow_note", JSONObject().put("type", "boolean")
                                .put("description", "是否允许用户补充备注；默认 true。")),
                    )
                    .put("required", JSONArray().put("title").put("question").put("options")),
            ),
        )
    }
}
