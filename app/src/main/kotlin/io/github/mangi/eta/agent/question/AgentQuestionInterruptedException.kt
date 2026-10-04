package io.github.mangi.eta.agent.question

/** A published question can no longer be answered safely; do not turn it into a retryable tool error. */
internal class AgentQuestionInterruptedException(cause: Throwable? = null) :
    RuntimeException("Question wait interrupted; this run cannot continue", cause)
