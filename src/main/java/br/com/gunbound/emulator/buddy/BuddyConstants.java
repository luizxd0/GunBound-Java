package br.com.gunbound.emulator.buddy;

/**
 * Buddy server protocol constants.
 * Opcodes extracted from BuddyServ2.exe string analysis
 * and confirmed via client packet captures.
 */
public final class BuddyConstants {

    private BuddyConstants() {
    }

    // ==========================================
    // Client -> Server opcodes
    // ==========================================

    /** Login request (initial handshake, no payload). */
    public static final int SVC_LOGIN_REQ = 0x1000;

    /** Login data (contains encrypted userId). */
    public static final int SVC_LOGIN_DATA = 0x1010;

    /** Add buddy request (16-byte null-padded nickname). */
    public static final int SVC_ADD_BUDDY = 0x3010;

    /** Remove buddy (16-byte null-padded friend id). */
    public static final int SVC_REMOVE_BUDDY = 0x3002;

    /** Buddy accept/reject (16-byte target + action byte). */
    public static final int BUDDY_ACCEPT_REJECT = 0x3000;

    /** User state change notification. */
    public static final int SVC_USER_STATE = 0x3011;

    /** Search users. */
    public static final int SVC_SEARCH = 0x4000;

    /** Tunnel packet (chat, status relay, buddy request via tunnel). */
    public static final int SVC_TUNNEL_PACKET = 0x2020;

    /** Legacy save/chat packet used by some clients. */
    public static final int SVC_SAVE_PACKET = 0x2000;

    /** Delete offline packets. */
    public static final int SVC_DELETE_PACKET = 0x2011;

    /** Buddy login (alternate login path: version + userId). */
    public static final int SVC_BUDDY_LOGIN = 0xA000;

    // ==========================================
    // Server -> Client opcodes
    // ==========================================

    /** Login response (4-byte auth token). */
    public static final int SVC_LOGIN_RESP = 0x1001;

    /** Buddy list entry (per-buddy 32-byte packet). */
    public static final int SVC_BUDDY_LIST = 0x1011;

    /** Add buddy response / buddy info. */
    public static final int SVC_ADD_BUDDY_RESP = 0x3001;

    /** Remove buddy response. */
    public static final int SVC_REMOVE_BUDDY_RESP = 0x3003;

    /** Relay buddy request / status / chat to target. */
    public static final int SVC_RELAY_BUDDY_REQ = 0x2021;

    /** User sync packet (end-of-list marker). */
    public static final int SVC_USER_SYNC = 0x3FFF;

    /** UDP context / token (6 bytes) sent after login. */
    public static final int SVC_UDP_CONTEXT = 0x101F;

    /** Search response. */
    public static final int SVC_SEARCH_RESP = 0x4001;

    // ==========================================
    // Buddy server default port
    // ==========================================

    /** Default buddy server port from Setting.txt */
    public static final int DEFAULT_PORT = 8352;

    // ==========================================
    // Status constants
    // ==========================================

    /** Default online status bitmask */
    public static final int STATUS_ONLINE = 0x0012;
    public static final int STATUS_OFFLINE = 0x0000;

    // ==========================================
    // Default metadata for 0x2021 relay
    // ==========================================

    /**
     * 28-byte metadata template for 0x2021 relay packets.
     * Based on original BuddyServ2.exe behavior.
     */
    public static final byte[] META_2021_TEMPLATE = {
            0x51, (byte) 0xC0, 0x18, 0x00, 0x12, 0x00, (byte) 0x8D, 0x02,
            (byte) 0xD0, 0x07, (byte) 0xD3, 0x00, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xFF, 0x36, 0x03, 0x48, 0x00, 0x00, 0x00,
            0x00, 0x00, (byte) 0x8D, 0x02
    };

    /**
     * Tag bytes for buddy invitation relay (0x2021).
     * 13 bytes: 41 C0 09 00 00 00 00 00 00 00 00 00 20
     */
    public static final byte[] BUDDY_INVITE_TAG = {
            0x41, (byte) 0xC0, 0x09, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x20
    };

    /**
     * Sync tail bytes for 0x3FFF packets.
     * 13 bytes: 01 33 BF AB EA 00 00 33 BF AB EA 00 00
     */
    public static final byte[] SYNC_TAIL = {
            0x01, 0x33, (byte) 0xBF, (byte) 0xAB, (byte) 0xEA, 0x00, 0x00,
            0x33, (byte) 0xBF, (byte) 0xAB, (byte) 0xEA, 0x00, 0x00
    };
}
