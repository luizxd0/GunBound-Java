package br.com.gunbound.emulator.buddy;

import io.netty.channel.Channel;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton that replaces BuddyCenter2.exe entirely.
 * Tracks online users connected to the buddy server in-memory.
 * Thread-safe via ConcurrentHashMap.
 */
public final class BuddySessionManager {

    private static final BuddySessionManager INSTANCE = new BuddySessionManager();

    // userId (lowercase) -> BuddySession
    private final Map<String, BuddySession> byUserId = new ConcurrentHashMap<>();

    // Channel -> BuddySession (for channelInactive lookups)
    private final Map<Channel, BuddySession> byChannel = new ConcurrentHashMap<>();

    // udpNonce -> BuddySession
    private final Map<Integer, BuddySession> byNonce = new ConcurrentHashMap<>();

    private BuddySessionManager() {
    }

    public static BuddySessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * Register a user session after successful authentication.
     */
    public void registerSession(BuddySession session) {
        if (session.getUserId() == null)
            return;
        String key = session.getUserId().toLowerCase();

        // Remove any old session for the same user
        BuddySession old = byUserId.get(key);
        if (old != null && old.getChannel() != session.getChannel()) {
            byChannel.remove(old.getChannel());
        }

        byUserId.put(key, session);
        byChannel.put(session.getChannel(), session);
        System.out.println("BuddySessionManager: Registered session for " + session.getUserId()
                + ". Online: " + byUserId.size());
    }

    /**
     * Remove a user session on disconnect.
     */
    public BuddySession removeSession(Channel channel) {
        BuddySession session = byChannel.remove(channel);
        if (session != null && session.getUserId() != null) {
            String key = session.getUserId().toLowerCase();
            // Only remove from byUserId if it still points to this session
            byUserId.remove(key, session);
            System.out.println("BuddySessionManager: Removed session for " + session.getUserId()
                    + ". Online: " + byUserId.size());
        }
        return session;
    }

    /**
     * Get session by userId.
     */
    public BuddySession getSession(String userId) {
        if (userId == null)
            return null;
        return byUserId.get(userId.toLowerCase());
    }

    /**
     * Get session by channel.
     */
    public BuddySession getSessionByChannel(Channel channel) {
        return byChannel.get(channel);
    }

    public void registerNonce(int nonce, BuddySession session) {
        byNonce.put(nonce, session);
    }

    public BuddySession getSessionByNonce(int nonce) {
        return byNonce.get(nonce);
    }

    /**
     * Check if a user is online on the buddy server.
     */
    public boolean isOnline(String userId) {
        if (userId == null)
            return false;
        BuddySession s = byUserId.get(userId.toLowerCase());
        return s != null && s.isActive();
    }

    /**
     * Get all online sessions.
     */
    public Collection<BuddySession> getAllSessions() {
        return byUserId.values();
    }

    /**
     * Get count of online users.
     */
    public int getOnlineCount() {
        return byUserId.size();
    }
}
