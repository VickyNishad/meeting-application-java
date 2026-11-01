package com.example.meetplatform.meeting.service;

import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Role -> permission catalogue loaded from the {@code role_permissions} reference table (seeded by Flyway),
 * so the matrix is data rather than scattered if-statements.
 */
@Component
public class MeetingPermissions {

    public static final String EDIT = "MEETING_EDIT";
    public static final String END = "MEETING_END";
    public static final String MODERATE = "MEETING_MODERATE";
    public static final String ASSIGN_ROLES = "MEETING_ASSIGN_ROLES";
    public static final String RECORD = "MEETING_RECORD";
    public static final String SCREEN_SHARE = "MEETING_SCREEN_SHARE";
    public static final String CHAT = "MEETING_CHAT";
    public static final String FILE_SHARE = "MEETING_FILE_SHARE";
    public static final String RECORDING_VIEW = "RECORDING_VIEW";
    public static final String RECORDING_MANAGE = "RECORDING_MANAGE";

    private final JdbcTemplate jdbc;
    private volatile Map<MeetingRole, Set<String>> matrix = new EnumMap<>(MeetingRole.class);

    public MeetingPermissions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        Map<MeetingRole, Set<String>> loaded = new EnumMap<>(MeetingRole.class);
        for (MeetingRole role : MeetingRole.values()) {
            loaded.put(role, new HashSet<>());
        }
        jdbc.query("select role_code, permission_code from role_permissions", rs -> {
            try {
                loaded.get(MeetingRole.valueOf(rs.getString(1))).add(rs.getString(2));
            } catch (IllegalArgumentException notAMeetingRole) {
                // system / organization roles are handled elsewhere
            }
        });
        this.matrix = loaded;
    }

    public boolean has(MeetingRole role, String permission) {
        if (matrix.isEmpty()) {
            load();
        }
        return role != null && matrix.getOrDefault(role, Set.of()).contains(permission);
    }
}
