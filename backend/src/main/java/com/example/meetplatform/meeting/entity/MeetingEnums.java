package com.example.meetplatform.meeting.entity;

import com.fasterxml.jackson.annotation.JsonAlias;

public final class MeetingEnums {

    private MeetingEnums() {
    }

    public enum MeetingType { INSTANT, SCHEDULED, PERSONAL_ROOM, ONE_TO_ONE }

    public enum MeetingStatus { SCHEDULED, LIVE, ENDED, CANCELLED }

    public enum MediaMode { VIDEO, AUDIO_ONLY }

    /** LINK: anyone holding the link; ORGANIZATION: members of the meeting's organization; INVITEES_ONLY: invited accounts. */
    public enum Privacy { LINK, ORGANIZATION, INVITEES_ONLY }

    public enum ScreenSharePolicy { EVERYONE, HOSTS_ONLY }

    /** The three media the host controls, each with its own permission. */
    public enum MediaKind { MICROPHONE, CAMERA, SCREEN_SHARE }

    /** A meeting-wide media permission or a participant's override (ALLOWED / RESTRICTED). */
    public enum MediaPermission { ALLOWED, RESTRICTED }

    /**
     * EVERYONE_VISIBLE: all participants see and hear each other. HOST_AND_SELF_ONLY: attendees see and hear only the
     * host, co-hosts and themselves; the host and co-hosts see everyone (enforced by the SFU with two rooms per
     * session). The earlier names EVERYONE and HOSTS_ONLY are still accepted on input.
     */
    public enum VisibilityMode {
        @JsonAlias("EVERYONE") EVERYONE_VISIBLE,
        @JsonAlias("HOSTS_ONLY") HOST_AND_SELF_ONLY
    }

    public enum MeetingRole { HOST, CO_HOST, PARTICIPANT, GUEST;

        public boolean isModerator() {
            return this == HOST || this == CO_HOST;
        }
    }
}
