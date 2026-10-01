package dev.sandeep.mcp.server.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.sandeep.mcp.server.domain.Customer;
import dev.sandeep.mcp.server.domain.CustomerOrder;
import dev.sandeep.mcp.server.domain.Enums.Channel;
import dev.sandeep.mcp.server.domain.Enums.Intent;
import dev.sandeep.mcp.server.domain.Enums.PaymentStatus;
import dev.sandeep.mcp.server.domain.Enums.RefundStatus;
import dev.sandeep.mcp.server.domain.Enums.RefundType;
import dev.sandeep.mcp.server.domain.Enums.Sentiment;
import dev.sandeep.mcp.server.domain.Enums.TicketStatus;
import dev.sandeep.mcp.server.domain.Payment;
import dev.sandeep.mcp.server.domain.Refund;
import dev.sandeep.mcp.server.domain.SupportTicket;
import dev.sandeep.mcp.server.dto.SupportDtos.RefundResult;
import dev.sandeep.mcp.server.dto.SupportDtos.TicketLogResult;
import dev.sandeep.mcp.server.repository.CustomerRepository;
import dev.sandeep.mcp.server.repository.OrderRepository;
import dev.sandeep.mcp.server.repository.PaymentRepository;
import dev.sandeep.mcp.server.repository.ProductRepository;
import dev.sandeep.mcp.server.repository.RefundRepository;
import dev.sandeep.mcp.server.repository.SupportTicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * State-changing MCP tools: the actions the agent <em>takes</em> once it has
 * decided on a resolution — issue a refund (and mark the reversed payment) and
 * log the interaction as a support ticket. Every method here writes to the DB.
 * <p>
 * Transactions are driven by a {@link TransactionTemplate} rather than
 * {@code @Transactional}: LangChain4j's {@code McpServer} discovers {@code @Tool}
 * methods via {@code getClass().getDeclaredMethods()}, and a transactional CGLIB
 * proxy would hide those annotations.
 */
@Service
public class SupportActionTools {

    private final CustomerRepository customers;
    private final ProductRepository products;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final SupportTicketRepository tickets;
    private final TransactionTemplate tx;

    public SupportActionTools(CustomerRepository customers,
                              ProductRepository products,
                              OrderRepository orders,
                              PaymentRepository payments,
                              RefundRepository refunds,
                              SupportTicketRepository tickets,
                              PlatformTransactionManager txManager) {
        this.customers = customers;
        this.products = products;
        this.orders = orders;
        this.payments = payments;
        this.refunds = refunds;
        this.tickets = tickets;
        this.tx = new TransactionTemplate(txManager);
    }

    // ---------------------------------------------------------------------
    // Take action: issue a refund
    // ---------------------------------------------------------------------

    @Tool(name = "issue_refund",
            value = "Issue a refund against an order and record it. Optionally tie it to a "
                    + "specific charge by transaction reference (e.g. when reversing one half of "
                    + "a duplicate charge), which marks that payment as REFUNDED. This takes real "
                    + "action — only call once you have decided a refund is warranted.")
    public RefundResult issueRefund(
            @P("The order number to refund against, e.g. 4471")
            String orderNumber,
            @P("The amount to refund, e.g. 199.99")
            BigDecimal amount,
            @P("Why the money is going back")
            RefundType refundType,
            @P("A short human-readable reason for the refund")
            String reason,
            @P(required = false, value = "Transaction reference of the specific "
                    + "charge being reversed; optional. If given, that payment is marked REFUNDED.")
            String transactionRef) {
        return tx.execute(status -> {
            CustomerOrder order = requireOrder(orderNumber);

            Refund refund = new Refund();
            refund.setOrder(order);
            refund.setAmount(amount);
            refund.setCurrency(order.getCurrency());
            refund.setReason(reason);
            refund.setRefundType(refundType);
            refund.setStatus(RefundStatus.PROCESSED);

            if (transactionRef != null && !transactionRef.isBlank()) {
                Payment payment = payments.findByTransactionRef(transactionRef.trim())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No payment found with transaction reference: " + transactionRef));
                payment.setStatus(PaymentStatus.REFUNDED);
                refund.setPayment(payment);
            }

            Refund saved = refunds.save(refund);
            String summary = "Refund of %s %s processed on order %s (%s): %s"
                    .formatted(amount, order.getCurrency(), orderNumber, refundType, reason);
            return new RefundResult(saved.getId(), orderNumber, amount, order.getCurrency(),
                    refundType.name(), saved.getStatus().name(), summary);
        });
    }

    // ---------------------------------------------------------------------
    // Record what happened: log the ticket
    // ---------------------------------------------------------------------

    @Tool(name = "log_support_ticket",
            value = "Record this email interaction as a support ticket, capturing the raw "
                    + "message, detected language, classified intent/sentiment, and the "
                    + "resolution. Call this last to log what was decided and done.")
    public TicketLogResult logSupportTicket(
            @P("Email of the customer the ticket is for")
            String customerEmail,
            @P("The customer's original message, verbatim")
            String rawMessage,
            @P("What the email is about")
            Intent intent,
            @P("The customer's tone")
            Sentiment sentiment,
            @P("Short subject line summarising the email")
            String subject,
            @P("Detected language code(s), e.g. en, hi, or en+hi")
            String detectedLanguage,
            @P("What was decided/done and the gist of the reply sent back")
            String resolution,
            @P(required = false, value = "Related order number, if any")
            String orderNumber,
            @P(required = false, value = "Related product SKU, if any")
            String sku) {
        return tx.execute(status -> {
            Customer customer = customers.findByEmailIgnoreCase(customerEmail.trim())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No customer account found for email: " + customerEmail));

            SupportTicket ticket = new SupportTicket();
            ticket.setCustomer(customer);
            ticket.setChannel(Channel.EMAIL);
            ticket.setRawMessage(rawMessage);
            ticket.setIntent(intent);
            ticket.setSentiment(sentiment);
            ticket.setSubject(subject);
            ticket.setDetectedLanguage(detectedLanguage);
            ticket.setResolution(resolution);
            ticket.setStatus(TicketStatus.RESOLVED);
            ticket.setResolvedAt(LocalDateTime.now());

            if (orderNumber != null && !orderNumber.isBlank()) {
                orders.findByOrderNumber(orderNumber.trim()).ifPresent(ticket::setOrder);
            }
            if (sku != null && !sku.isBlank()) {
                products.findBySkuIgnoreCase(sku.trim()).ifPresent(ticket::setProduct);
            }

            SupportTicket saved = tickets.save(ticket);
            return new TicketLogResult(saved.getId(), saved.getStatus().name(),
                    "Logged ticket #%d for %s.".formatted(saved.getId(), customerEmail));
        });
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private CustomerOrder requireOrder(String orderNumber) {
        return orders.findByOrderNumber(orderNumber.trim())
                .orElseThrow(() -> new IllegalArgumentException("No order found with number: " + orderNumber));
    }
}
