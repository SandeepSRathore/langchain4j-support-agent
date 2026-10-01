package com.eazybytes.mcp.server.tool;

import com.eazybytes.mcp.server.domain.Customer;
import com.eazybytes.mcp.server.domain.CustomerOrder;
import com.eazybytes.mcp.server.domain.Enums.PaymentStatus;
import com.eazybytes.mcp.server.domain.OrderItem;
import com.eazybytes.mcp.server.domain.Payment;
import com.eazybytes.mcp.server.domain.Product;
import com.eazybytes.mcp.server.domain.SupportTicket;
import com.eazybytes.mcp.server.dto.SupportDtos.CustomerInfo;
import com.eazybytes.mcp.server.dto.SupportDtos.DuplicateChargeResult;
import com.eazybytes.mcp.server.dto.SupportDtos.OrderDetails;
import com.eazybytes.mcp.server.dto.SupportDtos.OrderItemInfo;
import com.eazybytes.mcp.server.dto.SupportDtos.PaymentInfo;
import com.eazybytes.mcp.server.dto.SupportDtos.ProductInfo;
import com.eazybytes.mcp.server.dto.SupportDtos.TicketHistory;
import com.eazybytes.mcp.server.dto.SupportDtos.TicketInfo;
import com.eazybytes.mcp.server.dto.SupportDtos.WarrantyStatus;
import com.eazybytes.mcp.server.repository.CustomerRepository;
import com.eazybytes.mcp.server.repository.OrderRepository;
import com.eazybytes.mcp.server.repository.PaymentRepository;
import com.eazybytes.mcp.server.repository.ProductRepository;
import com.eazybytes.mcp.server.repository.SupportTicketRepository;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only MCP tools: everything the agent uses to <em>understand</em> an email
 * before it acts — identify the customer, pull orders and products, detect a
 * duplicate charge, check a warranty window, and review past tickets for repeat
 * failures. No method here mutates state.
 * <p>
 * Transactions are driven by a {@link TransactionTemplate} rather than
 * {@code @Transactional}: LangChain4j's {@code McpServer} discovers {@code @Tool}
 * methods via {@code getClass().getDeclaredMethods()}, and a transactional CGLIB
 * proxy would hide those annotations.
 */
@Service
public class SupportQueryTools {

    private final CustomerRepository customers;
    private final ProductRepository products;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final SupportTicketRepository tickets;
    private final TransactionTemplate tx;

    public SupportQueryTools(CustomerRepository customers,
                             ProductRepository products,
                             OrderRepository orders,
                             PaymentRepository payments,
                             SupportTicketRepository tickets,
                             PlatformTransactionManager txManager) {
        this.customers = customers;
        this.products = products;
        this.orders = orders;
        this.payments = payments;
        this.tickets = tickets;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setReadOnly(true);
    }

    // ---------------------------------------------------------------------
    // Identify the customer
    // ---------------------------------------------------------------------

    @Tool(name = "lookup_customer_by_email",
            value = "Look up a customer account by their email address. Returns name, "
                    + "contact details, preferred language, and loyalty tier. Use this first to "
                    + "identify who sent the email and how to address them.")
    public CustomerInfo lookupCustomerByEmail(
            @P("The customer's email address, e.g. sarah.mitchell@example.com")
            String email) {
        return tx.execute(status -> {
            Customer c = customers.findByEmailIgnoreCase(email.trim())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No customer account found for email: " + email));
            return toCustomerInfo(c);
        });
    }

    // ---------------------------------------------------------------------
    // Pull the customer's orders
    // ---------------------------------------------------------------------

    @Tool(name = "get_customer_orders",
            value = "List all orders placed by a customer (most recent first), each with "
                    + "its line items and payments. Use this to find the order an email is about, "
                    + "or to see the customer's purchase history.")
    public List<OrderDetails> getCustomerOrders(
            @P("The customer's email address")
            String email) {
        return tx.execute(status -> orders.findByCustomerEmailIgnoreCaseOrderByOrderDateDesc(email.trim())
                .stream()
                .map(this::toOrderDetails)
                .toList());
    }

    @Tool(name = "get_order_by_number",
            value = "Fetch a single order by its order number (the reference customers "
                    + "quote, e.g. \"#4471\"), including line items and every payment charged "
                    + "against it.")
    public OrderDetails getOrderByNumber(
            @P("The order number exactly as referenced, digits only, e.g. 4471")
            String orderNumber) {
        return tx.execute(status -> toOrderDetails(requireOrder(orderNumber)));
    }

    // ---------------------------------------------------------------------
    // Products / pre-sales
    // ---------------------------------------------------------------------

    @Tool(name = "search_products",
            value = "Search the product catalog by name or SKU fragment. Use for pre-sales "
                    + "questions or to identify which product a customer is describing.")
    public List<ProductInfo> searchProducts(
            @P("A product name or SKU fragment, e.g. \"X200\" or \"blender\"")
            String query) {
        return tx.execute(status -> {
            String q = query.trim();
            return products.findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(q, q)
                    .stream()
                    .map(this::toProductInfo)
                    .toList();
        });
    }

    @Tool(name = "get_product_by_sku",
            value = "Get full details for one product by its SKU, including the "
                    + "specifications JSON (voltage, dimensions, materials, etc.). Use this to "
                    + "answer spec questions such as whether a product supports European voltage.")
    public ProductInfo getProductBySku(
            @P("The product SKU, e.g. X200 or BLND-300")
            String sku) {
        return tx.execute(status -> {
            Product p = products.findBySkuIgnoreCase(sku.trim())
                    .orElseThrow(() -> new IllegalArgumentException("No product found with SKU: " + sku));
            return toProductInfo(p);
        });
    }

    // ---------------------------------------------------------------------
    // Billing: duplicate charge detection
    // ---------------------------------------------------------------------

    @Tool(name = "detect_duplicate_charges",
            value = "Analyse the payments on an order to detect a duplicate/double charge. "
                    + "Compares the total captured against the order total and flags repeated "
                    + "charges of the same amount. Use when a customer says they were charged twice.")
    public DuplicateChargeResult detectDuplicateCharges(
            @P("The order number to inspect, e.g. 4471")
            String orderNumber) {
        return tx.execute(status -> {
            CustomerOrder order = requireOrder(orderNumber);
            List<Payment> captured = payments.findByOrderOrderNumberOrderByChargedAtAsc(orderNumber.trim())
                    .stream()
                    .filter(p -> p.getStatus() == PaymentStatus.CAPTURED)
                    .toList();

            BigDecimal expected = order.getTotalAmount();
            BigDecimal totalCharged = captured.stream()
                    .map(Payment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal overcharged = totalCharged.subtract(expected).max(BigDecimal.ZERO);
            boolean duplicate = captured.size() > 1 && overcharged.signum() > 0;

            String summary = duplicate
                    ? "Duplicate charge detected: order %s was charged %d times totalling %s %s, but the order total is only %s %s — overcharged by %s %s. Refund one charge."
                            .formatted(orderNumber, captured.size(), totalCharged, order.getCurrency(),
                                    expected, order.getCurrency(), overcharged, order.getCurrency())
                    : "No duplicate detected: order %s has %d captured charge(s) totalling %s %s against an order total of %s %s."
                            .formatted(orderNumber, captured.size(), totalCharged, order.getCurrency(),
                                    expected, order.getCurrency());

            return new DuplicateChargeResult(orderNumber, duplicate, captured.size(), totalCharged,
                    expected, overcharged, captured.stream().map(this::toPaymentInfo).toList(), summary);
        });
    }

    // ---------------------------------------------------------------------
    // Warranty window
    // ---------------------------------------------------------------------

    @Tool(name = "check_warranty",
            value = "Check whether a product on an order is still within its warranty "
                    + "window, based on the order date plus the product's warranty length. Use "
                    + "before approving a warranty-based refund or replacement.")
    public WarrantyStatus checkWarranty(
            @P("The order number the product was bought on, e.g. 4198")
            String orderNumber,
            @P(required = false, value = "The product SKU to check; optional if "
                    + "the order has a single line item")
            String sku) {
        return tx.execute(status -> {
            CustomerOrder order = requireOrder(orderNumber);
            OrderItem item = resolveItem(order, sku);
            Product product = item.getProduct();

            LocalDate end = order.getOrderDate().plusMonths(product.getWarrantyMonths());
            boolean inWarranty = !LocalDate.now().isAfter(end);
            String summary = inWarranty
                    ? "%s on order %s is IN warranty (purchased %s, %d-month warranty ends %s)."
                            .formatted(product.getName(), orderNumber, order.getOrderDate(),
                                    product.getWarrantyMonths(), end)
                    : "%s on order %s is OUT of warranty (purchased %s, warranty expired %s)."
                            .formatted(product.getName(), orderNumber, order.getOrderDate(), end);

            return new WarrantyStatus(orderNumber, product.getSku(), product.getName(),
                    order.getOrderDate(), product.getWarrantyMonths(), end, inWarranty, summary);
        });
    }

    // ---------------------------------------------------------------------
    // History: repeat-failure detection
    // ---------------------------------------------------------------------

    @Tool(name = "get_customer_ticket_history",
            value = "Retrieve the customer's past support tickets (most recent first) so "
                    + "you can recognise repeat failures or recurring complaints — e.g. the same "
                    + "product breaking for the third time, which warrants goodwill.")
    public TicketHistory getCustomerTicketHistory(
            @P("The customer's email address")
            String email) {
        return tx.execute(status -> {
            List<SupportTicket> found =
                    tickets.findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(email.trim());
            List<TicketInfo> infos = found.stream().map(this::toTicketInfo).toList();
            String summary = "Customer %s has %d prior ticket(s) on record.".formatted(email, found.size());
            return new TicketHistory(email, found.size(), infos, summary);
        });
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private CustomerOrder requireOrder(String orderNumber) {
        return orders.findByOrderNumber(orderNumber.trim())
                .orElseThrow(() -> new IllegalArgumentException("No order found with number: " + orderNumber));
    }

    private OrderItem resolveItem(CustomerOrder order, String sku) {
        List<OrderItem> items = order.getItems();
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Order " + order.getOrderNumber() + " has no line items.");
        }
        if (sku == null || sku.isBlank()) {
            if (items.size() > 1) {
                throw new IllegalArgumentException("Order " + order.getOrderNumber()
                        + " has multiple items; specify a SKU to check warranty.");
            }
            return items.get(0);
        }
        return items.stream()
                .filter(i -> i.getProduct().getSku().equalsIgnoreCase(sku.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Order " + order.getOrderNumber() + " does not contain SKU " + sku));
    }

    private CustomerInfo toCustomerInfo(Customer c) {
        return new CustomerInfo(c.getId(), c.getFullName(), c.getEmail(), c.getPhone(),
                c.getPreferredLanguage(), c.getLoyaltyTier().name());
    }

    private ProductInfo toProductInfo(Product p) {
        return new ProductInfo(p.getId(), p.getSku(), p.getName(), p.getDescription(),
                p.getCategory(), p.getPrice(), p.getCurrency(), p.getSpecifications(),
                p.getWarrantyMonths(), p.getStockQuantity());
    }

    private OrderDetails toOrderDetails(CustomerOrder o) {
        List<OrderItemInfo> items = o.getItems().stream()
                .map(i -> new OrderItemInfo(i.getProduct().getSku(), i.getProduct().getName(),
                        i.getQuantity(), i.getUnitPrice()))
                .toList();
        List<PaymentInfo> pays = o.getPayments().stream().map(this::toPaymentInfo).toList();
        Customer c = o.getCustomer();
        return new OrderDetails(o.getOrderNumber(), c.getFullName(), c.getEmail(), o.getOrderDate(),
                o.getStatus().name(), o.getShippingAddress(), o.getTotalAmount(), o.getCurrency(),
                items, pays);
    }

    private PaymentInfo toPaymentInfo(Payment p) {
        return new PaymentInfo(p.getId(), p.getAmount(), p.getCurrency(), p.getPaymentMethod(),
                p.getTransactionRef(), p.getStatus().name(), p.getChargedAt());
    }

    private TicketInfo toTicketInfo(SupportTicket t) {
        return new TicketInfo(t.getId(), t.getSubject(), t.getIntent().name(),
                t.getSentiment().name(), t.getStatus().name(), t.getResolution(), t.getCreatedAt());
    }
}
