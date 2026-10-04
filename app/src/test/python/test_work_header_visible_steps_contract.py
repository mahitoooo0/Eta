"""AgentWorkProcessHeader keeps the card open while exiting steps are still visible.

Source contract only. hasVisibleSteps selects First vs Whole and the divider.
expanded remains the chevron, accessibility label, and toggle target.
"""
from pathlib import Path
import re
import unittest

from test_agent_chat_viewport_contract import balanced_end, code_only


SOURCE = (Path(__file__).resolve().parents[3] /
          "src/main/kotlin/io/github/mangi/eta/ui/components/ChatMessageItem.kt")


def function_source(source, name):
    match = re.search(rf"\bfun\s+{re.escape(name)}\s*\(", source)
    if match is None:
        raise AssertionError(f"Missing function {name}")
    open_paren = source.index("(", match.start())
    close_paren = balanced_end(source, open_paren, "(", ")")
    open_brace = source.index("{", close_paren)
    close_brace = balanced_end(source, open_brace, "{", "}")
    return source[match.start():close_brace + 1], source[open_paren + 1:close_paren], source[open_brace + 1:close_brace]


class WorkHeaderVisibleStepsContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fn, cls.params, cls.body = function_source(
            code_only(SOURCE.read_text(encoding="utf-8")),
            "AgentWorkProcessHeader",
        )

    def test_optional_visible_steps_defaults_to_expanded(self):
        self.assertRegex(
            self.params,
            r"expanded:\s*Boolean\s*,\s*hasVisibleSteps:\s*Boolean\s*=\s*expanded\s*,",
        )

    def test_visible_steps_select_slice_and_divider_only(self):
        self.assertIn(
            "if (hasVisibleSteps && messages.isNotEmpty()) WorkProcessCardPart.First else WorkProcessCardPart.Whole",
            self.body,
        )
        self.assertIn("if (hasVisibleSteps && messages.isNotEmpty())", self.body)
        self.assertNotIn("if (expanded && messages.isNotEmpty())", self.body)

    def test_expanded_still_drives_icon_label_and_toggle(self):
        self.assertIn("if (expanded) Icons.Rounded.ExpandMore", self.body)
        self.assertIn("if (expanded) R.string.work_collapse else R.string.work_expand", self.body)
        self.assertRegex(self.body, r"\bonClick\s*=\s*onToggle\b")
