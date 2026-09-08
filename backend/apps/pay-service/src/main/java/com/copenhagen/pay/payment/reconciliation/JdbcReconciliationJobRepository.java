package com.copenhagen.pay.payment.reconciliation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.copenhagen.pay.payment.policy.ReconciliationJobStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReconciliationJobRepository implements ReconciliationJobRepository {

    private static final String CLAIM_NEXT_SQL = """
            WITH candidate AS (
                SELECT id
                FROM payment_reconciliation_jobs
                WHERE ((status IN ('PENDING', 'FAILED') AND next_run_at <= ?)
                   OR (status = 'RUNNING' AND lease_expires_at IS NOT NULL
                       AND lease_expires_at <= ?))
                ORDER BY next_run_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE payment_reconciliation_jobs job
            SET status = 'RUNNING',
                lease_expires_at = ?,
                attempt_count = attempt_count + 1,
                last_run_at = ?
            FROM candidate
            WHERE job.id = candidate.id
            RETURNING job.id, job.payment_attempt_id, job.status,
                      job.attempt_count, job.lease_expires_at
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT id, payment_attempt_id, status, attempt_count, lease_expires_at
            FROM payment_reconciliation_jobs
            WHERE id = ?
            """;

    private static final String COMPLETE_SQL = """
            UPDATE payment_reconciliation_jobs
            SET status = ?,
                next_run_at = ?,
                lease_expires_at = NULL,
                last_run_at = ?,
                last_error = ?
            WHERE id = ? AND status = 'RUNNING'
            """;

    private static final RowMapper<ReconciliationJob> ROW_MAPPER =
            JdbcReconciliationJobRepository::mapJob;

    private final JdbcTemplate jdbcTemplate;

    public JdbcReconciliationJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ReconciliationJob> claimNext(Instant now, Instant leaseExpiresAt) {
        List<ReconciliationJob> jobs = jdbcTemplate.query(
                CLAIM_NEXT_SQL,
                statement -> {
                    statement.setTimestamp(1, Timestamp.from(now));
                    statement.setTimestamp(2, Timestamp.from(now));
                    statement.setTimestamp(3, Timestamp.from(leaseExpiresAt));
                    statement.setTimestamp(4, Timestamp.from(now));
                },
                ROW_MAPPER);
        return jobs.stream().findFirst();
    }

    @Override
    public Optional<ReconciliationJob> findById(String jobId) {
        List<ReconciliationJob> jobs = jdbcTemplate.query(
                FIND_BY_ID_SQL,
                ROW_MAPPER,
                jobId);
        return jobs.stream().findFirst();
    }

    @Override
    public boolean complete(
            String jobId,
            ReconciliationJobStatus targetStatus,
            Instant nextRunAt,
            Instant completedAt,
            String lastError) {
        int updated = jdbcTemplate.update(
                COMPLETE_SQL,
                targetStatus.name(),
                Timestamp.from(nextRunAt),
                Timestamp.from(completedAt),
                lastError,
                jobId);
        return updated == 1;
    }

    private static ReconciliationJob mapJob(ResultSet resultSet, int rowNumber)
            throws SQLException {
        Timestamp leaseTimestamp = resultSet.getTimestamp("lease_expires_at");
        return new ReconciliationJob(
                resultSet.getString("id"),
                resultSet.getString("payment_attempt_id"),
                ReconciliationJobStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count"),
                leaseTimestamp == null ? null : leaseTimestamp.toInstant());
    }
}
