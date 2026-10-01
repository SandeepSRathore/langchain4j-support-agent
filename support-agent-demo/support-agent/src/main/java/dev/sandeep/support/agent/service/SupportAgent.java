package dev.sandeep.support.agent.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.sandeep.support.agent.model.AgentResponse;
import dev.sandeep.support.agent.model.IncomingEmail;

import static dev.langchain4j.service.spring.AiServiceWiringMode.EXPLICIT;

/**
 * The brain of the agent: a declarative LangChain4j AI service, wired to the OpenAI
 * chat model and to the support tools exposed by the MCP server, that lets the LLM
 * drive the whole resolution autonomously. It reads the email, calls whatever MCP
 * tools it needs to identify the customer and gather facts, decides on an action,
 * takes it, and logs a ticket.
 * <p>
 * We never orchestrate the individual tool calls here — the {@code mcpToolProvider}
 * bean (see {@code McpClientConfig}) is handed to the AI service, and LangChain4j runs
 * the tool-calling loop for us. Our job is just to give the model its instructions (the
 * system prompt) and the email to act on; the {@link AgentResponse} return type makes
 * LangChain4j request and parse a structured JSON answer.
 */
@AiService(wiringMode = EXPLICIT, chatModel = "openAiChatModel", toolProvider = "mcpToolProvider")
public interface SupportAgent {

    /**
     * Hand a single email to the LLM and let it resolve the case end to end.
     *
     * @param email          the email that just landed in the support inbox
     * @param supportAddress the shared mailbox the agent works on behalf of
     * @return the agent's structured outcome: the reply to send to the customer
     *         plus an internal summary of what it understood and did.
     */
    default AgentResponse resolve(IncomingEmail email, String supportAddress) {
        return resolve(supportAddress, email.from(), String.join(", ", email.to()),
                String.valueOf(email.receivedAt()), email.subject(), email.body());
    }

    @SystemMessage(fromResource = "/prompts/support-agent-system.txt")
    @UserMessage("""
            A new email just arrived in the support inbox. Resolve it.

            From       : {{from}}
            To         : {{to}}
            Received   : {{receivedAt}}
            Subject    : {{subject}}

            Body:
            {{body}}
            """)
    AgentResponse resolve(@V("supportAddress") String supportAddress,
                          @V("from") String from,
                          @V("to") String to,
                          @V("receivedAt") String receivedAt,
                          @V("subject") String subject,
                          @V("body") String body);
}
