package io.github.mangi.eta.agent.question

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentQuestionCodecTest {
    @Test
    fun parsesValidArgumentsWithStableOwnership() {
        val request = parse(args(recommended = "\"a\""))

        assertEquals("q-1", request.questionId)
        assertEquals("c-1", request.conversationId)
        assertEquals("r-1", request.runId)
        assertEquals("call-1", request.toolCallId)
        assertEquals(1234L, request.createdAtMillis)
        assertEquals("选择路线", request.title)
        assertEquals("走哪条路线？", request.question)
        assertEquals(listOf("a", "b"), request.options.map { it.id })
        assertEquals("高速", request.options[0].label)
        assertEquals("风景好", request.options[1].description)
        assertEquals("a", request.recommendedOptionId)
        assertTrue(request.allowOther)
        assertTrue(request.allowDelegation)
        assertTrue(request.allowNote)
    }

    @Test
    fun noRecommendationIsEverSelectedAutomatically() {
        assertNull(parse(args()).recommendedOptionId)
        assertNull(parse(args(recommended = "null")).recommendedOptionId)
    }

    @Test
    fun explicitOptionSwitchesAreParsedStrictly() {
        val request = parse(args(allowOther = "false", allowDelegation = "false", allowNote = "false"))
        assertFalse(request.allowOther)
        assertFalse(request.allowDelegation)
        assertFalse(request.allowNote)
    }

    @Test
    fun rejectsMalformedOrOutOfBoundArguments() {
        val tooManyOptions = (1..9).joinToString(",") { "{\"id\":\"o$it\",\"label\":\"o$it\"}" }
        val oversized = "x".repeat(25_000)
        val invalid = listOf(
            "",
            "   ",
            "null",
            "[]",
            "42",
            "\"text\"",
            args(title = ""),
            args(title = "x".repeat(201)),
            args(question = "x".repeat(4_001)),
            "{\"title\":\"t\",\"options\":[{\"id\":\"a\",\"label\":\"a\"},{\"id\":\"b\",\"label\":\"b\"}]}",
            "{\"title\":\"t\",\"question\":\"q\"}",
            "{\"title\":\"t\",\"question\":\"q\",\"options\":[{\"id\":\"a\",\"label\":\"a\"}]}",
            "{\"title\":\"t\",\"question\":\"q\",\"options\":[$tooManyOptions]}",
            args(options = "{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"a\",\"label\":\"B\"}"),
            args(options = "{\"id\":\"\",\"label\":\"A\"},{\"id\":\"b\",\"label\":\"B\"}"),
            args(options = "{\"id\":\"${"x".repeat(65)}\",\"label\":\"A\"},{\"id\":\"b\",\"label\":\"B\"}"),
            args(options = "{\"id\":\"a\"},{\"id\":\"b\",\"label\":\"B\"}"),
            args(options = "{\"id\":\"a\",\"label\":\"${"x".repeat(201)}\"},{\"id\":\"b\",\"label\":\"B\"}"),
            args(options = "{\"id\":\"a\",\"label\":\"A\",\"description\":\"${"x".repeat(1_001)}\"},{\"id\":\"b\",\"label\":\"B\"}"),
            args(recommended = "\"missing\""),
            args(recommended = "5"),
            args(recommended = "{}"),
            args(allowOther = "\"true\""),
            args(allowNote = "1"),
            args(allowOther = "null"),
            "{\"title\":\"t\",\"question\":\"q\",\"options\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"b\",\"label\":\"B\"}],\"extra\":1}",
            "{\"title\":\"t\",\"question\":\"q\",\"options\":[{\"id\":\"a\",\"label\":\"A\",\"extra\":1},{\"id\":\"b\",\"label\":\"B\"}]}",
            "{\"title\":\"t\",\"question\":\"q\",\"options\":[\"a\",\"b\"]}",
            args(question = oversized),
        )

        invalid.forEachIndexed { index, json ->
            assertThrows("index=$index 应被拒绝", IllegalArgumentException::class.java) { parse(json) }
        }
    }

    @Test
    fun argumentsOverByteCapAreRejectedBeforeFieldLimits() {
        val oversized = "x".repeat(AgentQuestionCodec.MAX_ARGUMENTS_BYTES)
        assertTrue(args(question = oversized).toByteArray(Charsets.UTF_8).size > AgentQuestionCodec.MAX_ARGUMENTS_BYTES)
        assertThrows(IllegalArgumentException::class.java) { parse(args(question = oversized)) }
    }

    @Test
    fun blankQuestionIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentQuestionCodec.parseArguments("{}", "c-1", "r-1", "call-1", "  ", 0L)
        }
    }

    @Test
    fun requestJsonRoundTripPreservesOwnershipAndOptions() {
        val request = parse(
            args(recommended = "\"b\"", allowOther = "false", allowDelegation = "false", allowNote = "false"),
        )
        val restored = AgentQuestionCodec.requestFromJson(AgentQuestionCodec.requestToJson(request))
        assertEquals(request, restored)
    }

    @Test
    fun answerJsonRoundTripPreservesKindAndText() {
        val optionAnswer = AgentQuestionAnswer("option", optionId = "a", note = "备注")
        assertEquals(optionAnswer, AgentQuestionCodec.answerFromJson(AgentQuestionCodec.answerToJson(optionAnswer)))
        val otherAnswer = AgentQuestionAnswer("other", otherText = "都不合适")
        assertEquals(otherAnswer, AgentQuestionCodec.answerFromJson(AgentQuestionCodec.answerToJson(otherAnswer)))
        val delegateAnswer = AgentQuestionAnswer("delegate")
        assertEquals(delegateAnswer, AgentQuestionCodec.answerFromJson(AgentQuestionCodec.answerToJson(delegateAnswer)))
    }

    @Test
    fun resultProjectsOptionFromRuntimeRequestNotUiText() {
        val request = parse(args(recommended = "\"a\""))
        val result = AgentQuestionCodec.resultJson(
            request,
            AgentQuestionAnswer("option", optionId = "b", note = "选这条"),
        )

        assertEquals(AgentQuestionCodec.STATUS_ANSWERED, result.getString("status"))
        assertEquals("q-1", result.getString("question_id"))
        assertEquals("option", result.getString("kind"))
        val selected = result.getJSONObject(AgentQuestionCodec.KEY_SELECTED_OPTION)
        assertEquals("b", selected.getString("id"))
        assertEquals("省道", selected.getString("label"))
        assertEquals("风景好", selected.getString("description"))
        assertFalse(result.has("other_text"))
        assertEquals("选这条", result.getString("note"))
    }

    @Test
    fun resultCarriesOtherTextAndRespectsNoteSwitch() {
        val allowed = parse(args())
        val other = AgentQuestionCodec.resultJson(allowed, AgentQuestionAnswer("other", otherText = "路上加油"))
        assertEquals("other", other.getString("kind"))
        assertEquals("路上加油", other.getString("other_text"))
        assertFalse(other.has("note"))

        val noNote = parse(args(allowNote = "false"))
        val receipt = AgentQuestionCodec.validateAnswer(noNote, AgentQuestionAnswer("option", optionId = "a", note = "x"))
        assertFalse(receipt.accepted)
        assertEquals(AgentQuestionCodec.CODE_NOTE_NOT_ALLOWED, receipt.code)
        assertFalse(AgentQuestionCodec.resultJson(noNote, AgentQuestionAnswer("option", optionId = "a")).has("note"))
    }

    @Test
    fun resultNeverSucceedsForIllegalAnswer() {
        val request = parse(args())
        assertThrows(IllegalArgumentException::class.java) {
            AgentQuestionCodec.resultJson(request, AgentQuestionAnswer("option", optionId = "z"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentQuestionCodec.resultJson(request, AgentQuestionAnswer("option", optionId = "a", otherText = "混入"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentQuestionCodec.resultJson(request, AgentQuestionAnswer("other", otherText = "  "))
        }
    }

    @Test
    fun optionRequiresExistingIdAndNoOtherText() {
        val request = parse(args())
        assertEquals(AgentQuestionCodec.CODE_OPTION_REQUIRED,
            AgentQuestionCodec.validateAnswer(request, AgentQuestionAnswer("option")).code)
        assertEquals(AgentQuestionCodec.CODE_UNKNOWN_OPTION,
            AgentQuestionCodec.validateAnswer(request, AgentQuestionAnswer("option", optionId = "z")).code)
        assertEquals(AgentQuestionCodec.CODE_OPTION_WITH_OTHER_TEXT,
            AgentQuestionCodec.validateAnswer(request, AgentQuestionAnswer("option", optionId = "a", otherText = "x")).code)
        assertTrue(AgentQuestionCodec.validateAnswer(request, AgentQuestionAnswer("option", optionId = "a")).accepted)
    }

    @Test
    fun otherAndDelegateRespectSwitchesAndShape() {
        val allowed = parse(args())
        assertTrue(AgentQuestionCodec.validateAnswer(allowed, AgentQuestionAnswer("other", otherText = "自定义")).accepted)
        assertTrue(AgentQuestionCodec.validateAnswer(allowed, AgentQuestionAnswer("delegate", note = "稍后处理")).accepted)
        assertEquals(AgentQuestionCodec.CODE_OTHER_TEXT_REQUIRED,
            AgentQuestionCodec.validateAnswer(allowed, AgentQuestionAnswer("other")).code)
        assertEquals(AgentQuestionCodec.CODE_OTHER_WITH_OPTION_ID,
            AgentQuestionCodec.validateAnswer(allowed, AgentQuestionAnswer("other", optionId = "a", otherText = "x")).code)
        assertEquals(AgentQuestionCodec.CODE_DELEGATE_WITH_OPTION_ID,
            AgentQuestionCodec.validateAnswer(allowed, AgentQuestionAnswer("delegate", optionId = "a")).code)
        assertEquals(AgentQuestionCodec.CODE_DELEGATE_WITH_OTHER_TEXT,
            AgentQuestionCodec.validateAnswer(allowed, AgentQuestionAnswer("delegate", otherText = "x")).code)
        assertEquals(AgentQuestionCodec.CODE_INVALID_KIND,
            AgentQuestionCodec.validateAnswer(allowed, AgentQuestionAnswer("mystery")).code)

        val denied = parse(args(allowOther = "false", allowDelegation = "false"))
        assertEquals(AgentQuestionCodec.CODE_OTHER_NOT_ALLOWED,
            AgentQuestionCodec.validateAnswer(denied, AgentQuestionAnswer("other", otherText = "x")).code)
        assertEquals(AgentQuestionCodec.CODE_DELEGATION_NOT_ALLOWED,
            AgentQuestionCodec.validateAnswer(denied, AgentQuestionAnswer("delegate")).code)
    }

    @Test
    fun answerTextLimitsAreEnforced() {
        val request = parse(args())
        assertEquals(AgentQuestionCodec.CODE_OTHER_TEXT_TOO_LONG,
            AgentQuestionCodec.validateAnswer(request, AgentQuestionAnswer("other", otherText = "x".repeat(2_001))).code)
        assertTrue(AgentQuestionCodec.validateAnswer(request, AgentQuestionAnswer("other", otherText = "x".repeat(2_000))).accepted)
        assertEquals(AgentQuestionCodec.CODE_NOTE_TOO_LONG,
            AgentQuestionCodec.validateAnswer(request, AgentQuestionAnswer("option", optionId = "a", note = "x".repeat(2_001))).code)
    }

    @Test
    fun rejectsTrailingContentAfterTheArgumentsObject() {
        listOf(
            "${args()} trailing",
            "${args()}[]",
            "{}garbage",
            "${args()}\n{}\n",
        ).forEach { json ->
            assertThrows("应拒绝多余内容: $json", IllegalArgumentException::class.java) { parse(json) }
        }
    }

    @Test
    fun answerFromJsonStrictlyRejectsUnknownKindAndWrongTextTypes() {
        assertThrows(IllegalArgumentException::class.java) { AgentQuestionCodec.answerFromJson(JSONObject()) }
        assertThrows(IllegalArgumentException::class.java) {
            AgentQuestionCodec.answerFromJson(JSONObject().put("kind", 5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentQuestionCodec.answerFromJson(JSONObject().put("kind", "mystery"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentQuestionCodec.answerFromJson(JSONObject().put("kind", "option").put("other_text", 7))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentQuestionCodec.answerFromJson(JSONObject().put("kind", "other").put("other_text", JSONObject.NULL))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentQuestionCodec.answerFromJson(JSONObject().put("kind", "option").put("note", JSONObject.NULL))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentQuestionCodec.answerFromJson(JSONObject().put("kind", "option").put("note", false))
        }
        val decoded = AgentQuestionCodec.answerFromJson(
            JSONObject().put("kind", "option").put("option_id", "a").put("ignored", "x"),
        )
        assertEquals(AgentQuestionAnswer("option", optionId = "a"), decoded)
    }

    private fun parse(json: String) = AgentQuestionCodec.parseArguments(
        argumentsJson = json,
        conversationId = "c-1",
        runId = "r-1",
        toolCallId = "call-1",
        questionId = "q-1",
        createdAtMillis = 1234L,
    )

    private fun args(
        title: String = "选择路线",
        question: String = "走哪条路线？",
        options: String = "{\"id\":\"a\",\"label\":\"高速\"},{\"id\":\"b\",\"label\":\"省道\",\"description\":\"风景好\"}",
        recommended: String? = null,
        allowOther: String? = null,
        allowDelegation: String? = null,
        allowNote: String? = null,
    ): String = buildString {
        append("{\"title\":\"").append(title).append("\",")
        append("\"question\":\"").append(question).append("\",")
        append("\"options\":[").append(options).append("]")
        recommended?.let { append(",\"recommended_option_id\":").append(it) }
        allowOther?.let { append(",\"allow_other\":").append(it) }
        allowDelegation?.let { append(",\"allow_delegation\":").append(it) }
        allowNote?.let { append(",\"allow_note\":").append(it) }
        append("}")
    }
}
