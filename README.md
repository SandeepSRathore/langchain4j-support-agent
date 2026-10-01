# LangChain4j Support Agent

An autonomous e-mail customer-support agent for a fictional e-commerce shop, built with
**LangChain4j**, **Spring Boot 4** and the **Model Context Protocol (MCP)**.

The agent watches a support inbox. For every new e-mail it lets an LLM look up the customer,
their orders, payments, warranties and ticket history through MCP tools. The LLM then decides
on a resolution: it can issue a refund, logs a support ticket and sends a threaded reply back
to the customer. No human is involved in the loop.

> This is a LangChain4j port of the Spring AI `support-agent-demo` from
> [eazybytes/spring-ai (branch `2.x`, `section_11`)](https://github.com/eazybytes/spring-ai/tree/2.x/section_11/support-agent-demo).
> Behaviour, data model and prompts are kept the same; only the AI/MCP layer was rewritten.

## How it works

```
 customer e-mail                                     ┌────────────────────────┐
 ───────────────►  Mailpit (fake SMTP + inbox) ◄──── │     support-agent      │  :8080
                   :1025 SMTP / :8025 UI + API       │  polls inbox every 10s │
                                  ▲                  │  @AiService (OpenAI)   │
                    threaded reply│                  └───────────┬────────────┘
                                  └──────────────────────────────┤ MCP over
                                                                 │ Streamable HTTP
                                                     ┌───────────▼────────────┐
                                                     │       mcp-server       │  :8090/mcp
                                                     │  10 @Tool methods      │
                                                     │  JPA ──► MySQL :3307   │
                                                     └────────────────────────┘
```

1. `InboxMonitor` polls Mailpit's REST API for unread mail sent to `support@example.com`.
2. `SupportAgent`, a LangChain4j `@AiService`, gets the e-mail together with the tools from the
   MCP server. LangChain4j runs the tool-calling loop until the model is done.
3. The model returns a structured `AgentResponse` (reply subject, reply body, operator summary)
   as strict JSON-schema output.
4. `SupportMailSender` sends the reply over SMTP, threaded onto the original message.
5. If anything fails, the e-mail is put back to *unread* and retried on the next poll.

### MCP tools (served by `mcp-server`)

| Tool | Kind | Purpose |
|---|---|---|
| `lookup_customer_by_email` | read | Identify the sender, preferred language, loyalty tier |
| `get_customer_orders` / `get_order_by_number` | read | Orders with line items and payments |
| `search_products` / `get_product_by_sku` | read | Catalog and specs (e.g. voltage) for pre-sales questions |
| `detect_duplicate_charges` | read | Spot a double charge on an order |
| `check_warranty` | read | Is a product still within its warranty window? |
| `get_customer_ticket_history` | read | Recognise repeat failures |
| `issue_refund` | **write** | Record a refund (optionally reversing one specific charge) |
| `log_support_ticket` | **write** | Log the interaction and its resolution |

## Tech stack

- Java 25, Spring Boot 4.1, Maven (wrapper included)
- LangChain4j 1.20.2: `langchain4j-spring-boot4-starter`, `langchain4j-open-ai-spring-boot4-starter`,
  `langchain4j-mcp` (client) and `langchain4j-community-mcp-server` (server)
- OpenAI `gpt-4o-mini` (configurable)
- MySQL and [Mailpit](https://mailpit.axllent.org/), both started automatically through Docker Compose

### Spring AI → LangChain4j at a glance

| Spring AI (original) | LangChain4j (this repo) |
|---|---|
| `ChatClient` + `defaultSystem` + `defaultTools` | `@AiService` interface + `@SystemMessage(fromResource)` + `toolProvider` |
| `.call().entity(AgentResponse.class)` | method return type `AgentResponse` |
| `{param}` prompt templates | `{{param}}` + `@V` |
| `spring-ai-starter-mcp-client` | `StreamableHttpMcpTransport` → `DefaultMcpClient` → `McpToolProvider` |
| `@McpTool` / `@McpToolParam` | `@Tool` / `@P` |
| `spring-ai-starter-mcp-server-webmvc` | community `McpServer` behind a small `POST /mcp` controller |

LangChain4j's MCP server module ships only a stdio transport. `McpController` therefore exposes
it over Streamable HTTP: requests get a JSON reply, notifications get `202`, and `GET`/`DELETE`
get `405`.

## Prerequisites

- **JDK 25**
- **Docker** (Docker Desktop or similar), running
- An **OpenAI API key** with credits

## Getting started

```bash
git clone https://github.com/SandeepSRathore/langchain4j-support-agent.git
cd langchain4j-support-agent/support-agent-demo

# 1. Add your OpenAI key (this file is git-ignored)
cp support-agent/secrets.properties.example support-agent/secrets.properties
#    then edit support-agent/secrets.properties and paste your key
```

Start the two apps in **two terminals**. Each one starts its own Docker containers on first run.

```bash
# Terminal 1 — MCP server + MySQL (schema and demo data are loaded automatically)
cd mcp-server && ./mvnw spring-boot:run

# Terminal 2 — the agent + Mailpit (start it once terminal 1 logs "Started McpServerApplication")
cd support-agent && ./mvnw spring-boot:run
```

| Service | URL / port |
|---|---|
| Support agent (seed endpoint) | http://localhost:8080 |
| MCP server | http://localhost:8090/mcp |
| Mailpit inbox UI | http://localhost:8025 |
| MySQL | `localhost:3307`, database `mydatabase`, user `myuser` / `secret` |

> **Port already in use?** MySQL is mapped to host port **3307** so it doesn't clash with a
> locally installed MySQL. If something else already uses 8080, start the agent on another port:
> `./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"` and use that port in the
> `curl` commands below.

## Try it

Drop a test e-mail into the inbox with the seed endpoint. The agent picks it up within about 10 seconds.
The demo data contains four ready-made scenarios:

```bash
# 1. Duplicate charge → refunds exactly one of the two charges
curl -X POST localhost:8080/seed-mail \
  --data-urlencode "from=priya.sharma@example.com" \
  --data-urlencode "subject=Charged twice for order #4471" \
  --data-urlencode "body=I was charged twice for order #4471 (the knife set). Please refund the extra charge."

# 2. Third broken blender → recognises the repeat failure, goodwill refund
curl -X POST localhost:8080/seed-mail \
  --data-urlencode "from=sarah.mitchell@example.com" \
  --data-urlencode "subject=Blender jug cracked AGAIN - order 4198" \
  --data-urlencode "body=The jug on my AeroBlend 300 (order #4198) cracked again - the THIRD time. What can you do?"

# 3. Pre-sales question → answered from product specs
curl -X POST localhost:8080/seed-mail \
  --data-urlencode "from=james.cooper@example.com" \
  --data-urlencode "subject=Question about the X200" \
  --data-urlencode "body=I'm moving to Germany. Will the X200 work on European voltage?"

# 4. Sarcastic, Hinglish, two issues in one e-mail
curl -X POST localhost:8080/seed-mail \
  --data-urlencode "from=rohan.verma@example.com" \
  --data-urlencode "subject=Wah, kya service hai" \
  --data-urlencode "body=Order #4502 aaya, kettle plug karte hi band ho gaya aur hand mixer se jalne ki smell aa rahi hai. Refund chahiye."
```

Then check the result:

- **Agent log**: look for the `=== Agent resolution ===` summary for each e-mail.
- **Mailpit** at http://localhost:8025 shows the threaded replies. Avoid opening a message the
  agent is still retrying, because viewing it marks it as read and the agent will skip it.
- **Database**: refunds and tickets the agent wrote:
  ```bash
  docker exec -it support-agent-mysql mysql -umyuser -psecret mydatabase \
    -e "SELECT * FROM refunds; SELECT id, intent, sentiment, subject FROM support_tickets;"
  ```

You can also call the MCP server directly:

```bash
curl -s -X POST localhost:8090/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Configuration

Agent settings are in `support-agent/src/main/resources/application.properties`:

| Property | Default | Meaning |
|---|---|---|
| `langchain4j.open-ai.chat-model.model-name` | `gpt-4o-mini` | OpenAI model |
| `langchain4j.open-ai.chat-model.api-key` | from `secrets.properties` | OpenAI API key |
| `support-agent.inbox.poll-interval` | `10000` | Inbox poll interval (ms) |
| `support-agent.inbox.address` | `support@example.com` | Mailbox the agent works for |
| `support-agent.mcp.url` | `http://localhost:8090/mcp` | MCP server endpoint |
| `support-agent.mcp.log-traffic` | `false` | Log every MCP request/response |

The agent's instructions are in `support-agent/src/main/resources/prompts/support-agent-system.txt`.

## Running the tests

Both modules have a Spring context test. Docker must be running, and the agent's test also
needs the MCP server to be up:

```bash
cd mcp-server && docker compose up -d && ./mvnw verify
cd ../support-agent && ./mvnw verify      # with mcp-server running
```

## Project structure

```
support-agent-demo/
├── mcp-server/                     MCP server (port 8090) + MySQL
│   ├── compose.yaml                MySQL container (host port 3307)
│   ├── db/init/                    schema + demo data, loaded on first start
│   └── src/main/java/dev/sandeep/mcp/server/
│       ├── tool/                   @Tool methods (query + action tools)
│       ├── web/McpController.java  Streamable HTTP endpoint for the MCP server
│       ├── config/                 McpServer bean
│       └── domain/ repository/ dto/
└── support-agent/                  the agent (port 8080) + Mailpit
    ├── compose.yaml                Mailpit container
    ├── secrets.properties.example  copy to secrets.properties and add your key
    └── src/main/java/dev/sandeep/support/agent/
        ├── service/SupportAgent.java     the LangChain4j @AiService
        ├── config/McpClientConfig.java   MCP client + tool provider
        ├── service/InboxMonitor.java     polls the inbox
        └── service/SupportMailSender.java sends threaded replies
```

## Limitations

This is a **learning demo**. Don't expose it to a network you don't trust:

- The `/mcp` endpoint has **no authentication**, so anyone who can reach port 8090 can call
  `issue_refund`.
- E-mail content drives the refund tool, so a crafted e-mail could talk the model into a refund.
  `issue_refund` doesn't check the amount against what was charged.
- Database credentials are hard-coded for local use.

The cheap default model (`gpt-4o-mini`) sometimes skips the final `log_support_ticket` call or
reads sarcasm literally. A stronger model or a stricter prompt improves this, at a higher cost.

## Credits

Original Spring AI project, demo data and prompts:
[EazyBytes — spring-ai](https://github.com/eazybytes/spring-ai).
