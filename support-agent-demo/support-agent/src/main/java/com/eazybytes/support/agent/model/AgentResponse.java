package com.eazybytes.support.agent.model;

import dev.langchain4j.model.output.structured.Description;

/**
 * Structured outcome the LLM returns after working a single support email. It
 * separates the two audiences: {@code replySubject}/{@code replyBody} is the
 * message we actually send back to the customer, while {@code operatorSummary} is
 * an internal note for the human watching the logs.
 * <p>
 * The {@link Description}s are surfaced to the model as the JSON schema for its
 * response, so keep them instructive.
 */
@Description("The outcome of handling a support email: the reply to send to the customer plus an internal summary.")
public record AgentResponse(

        @Description("Subject line for the reply email to the customer, e.g. \"Re: Charged twice for order 4471\".")
        String replySubject,

        @Description("The complete, ready-to-send reply to the customer, written in their language and tone. "
                + "Plain text, properly greeting and signing off. No placeholders or bracketed TODOs.")
        String replyBody,

        @Description("A short internal summary for the human operator: who the customer was, what they "
                + "wanted, what you found, and what action you took (include any refund id / ticket id).")
        String operatorSummary
) {
}
