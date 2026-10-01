package dev.sandeep.mcp.server.repository;

import dev.sandeep.mcp.server.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {
}
