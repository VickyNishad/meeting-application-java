package com.example.meetplatform.common.audit;

import com.example.meetplatform.common.observability.ClientInfo;
import com.example.meetplatform.common.observability.TraceContext;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit trail. Entries join the caller's transaction so an audited change and its audit
 * record commit or roll back together. Never put secrets, tokens or signed URLs in {@code details}.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final CurrentUserProvider currentUser;
    private final TraceContext traceContext;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public AuditService(AuditLogRepository repository, CurrentUserProvider currentUser, TraceContext traceContext,
                        JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.traceContext = traceContext;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String resourceType, UUID resourceId, Map<String, ?> details) {
        record(currentUser.current().map(CurrentUser::userId).orElse(null), null, action, resourceType, resourceId, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorUserId, UUID actorParticipantId, String action, String resourceType, UUID resourceId,
                       Map<String, ?> details) {
        repository.save(new AuditLog(actorUserId, actorParticipantId, action, resourceType, resourceId,
                jsonMapper.writeValueAsString(details == null ? Map.of() : details),
                ClientInfo.current().ipAddress(), traceContext.currentTraceId(), clock.instant()));
    }
}
