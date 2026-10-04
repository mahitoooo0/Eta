"""Source-level stop wiring contracts; not a substitute for the JVM/Android tests."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
KOTLIN = ROOT / "app/src/main/kotlin/io/github/mangi/eta"


def source(relative):
    return (KOTLIN / relative).read_text(encoding="utf-8")


def method(text, name):
    start = re.search(r"^    (?:private |override )?fun " + re.escape(name) + r"\(", text, re.M)
    assert start, name
    following = re.search(r"^    (?:private |override )?fun ", text[start.end():], re.M)
    return text[start.start():start.end() + following.start()] if following else text[start.start():]


class StopResponsivenessContract(unittest.TestCase):
    def setUp(self):
        self.app = source("ui/app/AgentAppState.kt")
        self.service = source("agent/runtime/AgentRuntimeService.kt")

    def test_stop_captures_session_then_dispatches_blocking_work(self):
        body = method(self.service, "stopMainRun")
        dispatch = body.index("stopWorker.submit(session)")
        self.assertLess(body.index("val session = sessions.get(runId)"), dispatch)
        self.assertGreater(body.index("AgentChildRunControl.terminate(session, reason)"), dispatch)
        self.assertGreater(body.index("session.requestStop()"), dispatch)
        self.assertEqual(body.count("sessions.get(runId)"), 1)
        self.assertIn("finally", body)

    def test_worker_overlay_updates_are_main_thread_and_identity_guarded(self):
        body = method(self.service, "stopMainRun")
        self.assertLess(body.index("mainHandler.post"), body.index("state.value ="))
        self.assertIn("!destroyed && overlaySession === session && session.terminalResult == null", body)

    def test_destroy_does_not_synchronously_cancel_resources(self):
        body = method(self.service, "onDestroy")
        self.assertIn("destroyed = true", body)
        self.assertIn("stopWorker.close(retiring.map", body)
        self.assertNotIn("sessions.cancelAll(", body)
        self.assertGreater(body.index("session.cancel("), body.index("stopWorker.close("))

    def test_transport_is_cancelled_before_cleanup(self):
        body = method(source("agent/runtime/AgentRunController.kt"), "cancel")
        self.assertLess(body.index("cancelled = true"), body.index("sortedByDescending { it.interruptible }"))
        self.assertLess(body.index("sortedByDescending { it.interruptible }"), body.index("resource.cancel()"))

    def test_navigation_metadata_and_model_choice_ignore_only_stop_lock(self):
        for name in ("selectConversationContent", "createConversation", "selectFolder", "createFolder",
                     "renameFolder", "deleteFolder", "moveConversationToFolder", "toggleConversationPinned",
                     "renameConversation", "selectModel"):
            with self.subTest(name=name):
                self.assertIn("rejectConversationArchiveMutation(protectStoppingRun = false)", method(self.app, name))
        guard = method(self.app, "rejectConversationArchiveMutation")
        self.assertIn("protectStoppingRun: Boolean = true", guard)
        self.assertIn("if (protectStoppingRun &&", guard)
        self.assertIn("backupMaintenance", guard)
        self.assertIn("conversationArchiveBusy", guard)

    def test_history_mutation_and_sending_keep_stop_ownership_guard(self):
        for name in ("deleteConversation", "sendCurrentMessage"):
            with self.subTest(name=name):
                self.assertIn("rejectConversationArchiveMutation()", method(self.app, name))
        # Restoration shares an entry; stop ownership is checked before IO and publication.
        for name in ("beginMessageEdit", "deleteMessageTurn", "branchConversation"):
            with self.subTest(name=name):
                self.assertIn("launchConversationRevision(messageId)", method(self.app, name))
        transaction = method(self.app, "launchConversationRevision")
        self.assertLess(transaction.index("rejectConversationArchiveMutation()"),
                        transaction.index("scope.launch("))
        self.assertIn("stoppingRuns.keys.none { runConversationIds[it] == conversationId }", transaction)
        self.assertLess(transaction.index("if (!stillCurrent())"), transaction.index("publish(conversationId"))

    def test_duplicate_terminal_callback_preserves_watchdog(self):
        body = method(self.app, "finishStopSeal")
        self.assertLess(body.index("if (stopSealTerminalTimeout.isPending(runId)) return"),
                        body.index("stopSealWatchdogJobs.remove(runId)?.cancel()"))

    def test_stale_ticket_does_not_remove_new_watchdog(self):
        body = method(self.app, "armStopSealWatchdog").split("withContext(Dispatchers.Main.immediate)", 1)[1]
        self.assertLess(body.index("claimUnlock(ticket)"), body.index("stopSealWatchdogJobs.remove(runId)"))

    def test_late_stop_failure_does_not_reactivate_settled_run(self):
        body = method(self.app, "stopRun").split("if (!accepted) withContext", 1)[1]
        self.assertLess(body.index("if (!stoppingRuns.containsKey(runId)) return@withContext"),
                        body.index("current.copy(isStreaming = true"))

    def test_worker_drains_accepted_work_and_uses_owner_identity(self):
        body = source("agent/runtime/AgentRuntimeStopWorker.kt")
        self.assertIn("IdentityHashMap<Any, Boolean>()", body)
        self.assertIn("Executors.newCachedThreadPool", body)
        self.assertIn("if (closed || !pending.add(owner))", body)
        self.assertIn("executor.execute", body)
        self.assertIn("executor.shutdown()", body)
        self.assertNotIn("executor.shutdownNow()", body)


if __name__ == "__main__":
    unittest.main()
