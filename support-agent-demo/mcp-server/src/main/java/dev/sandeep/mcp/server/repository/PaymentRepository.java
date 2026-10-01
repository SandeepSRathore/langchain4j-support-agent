package dev.sandeep.mcp.server.repository;

import dev.sandeep.mcp.server.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByOrderOrderNumberOrderByChargedAtAsc(String orderNumber);

    Optional<Payment> findByTransactionRef(String transactionRef);
}
