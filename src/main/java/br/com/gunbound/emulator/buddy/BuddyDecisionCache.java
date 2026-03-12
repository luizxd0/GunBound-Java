package br.com.gunbound.emulator.buddy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Time-based dedup cache to prevent duplicate accept/reject processing.
 * The GunBound client sends BOTH 0x3000 (accept/reject) AND 0x2020 (tunnel accept)
 * for the same button press. This cache ensures only the first one is processed.
 */
public final class BuddyDecisionCache {

    private static final BuddyDecisionCache INSTANCE = new BuddyDecisionCache();

    /** Window in milliseconds to suppress duplicates */
    private static final long WINDOW_MS = 2000;

    /** Cache key = "actor|target|action" -> timestamp */
    private final Map<String, Long> cache = new ConcurrentHashMap<>();

    private BuddyDecisionCache() {}

    public static BuddyDecisionCache getInstance() {
        return INSTANCE;
    }

    /**
     * Returns true if this decision should be processed (first occurrence),
     * false if it's a duplicate within the dedup window.
     */
    public boolean shouldProcess(String actorUserId, String targetUserId, boolean isAccept) {
        if (actorUserId == null || targetUserId == null) return true;

        String key = actorUserId.toLowerCase() + "|" + targetUserId.toLowerCase() + "|" + (isAccept ? "1" : "0");
        long now = System.currentTimeMillis();

        Long prev = cache.get(key);
        if (prev != null && (now - prev) <= WINDOW_MS) {
            return false; // Duplicate — suppress
        }

        cache.put(key, now);

        // Cleanup old entries
        cache.entrySet().removeIf(e -> (now - e.getValue()) > 10000);

        return true;
    }

    /**
     * Returns true if the same decision was processed recently, without inserting/updating.
     */
    public boolean isRecentDecision(String actorUserId, String targetUserId, boolean isAccept, long windowMs) {
        if (actorUserId == null || targetUserId == null) return false;

        String key = actorUserId.toLowerCase() + "|" + targetUserId.toLowerCase() + "|" + (isAccept ? "1" : "0");
        Long prev = cache.get(key);
        if (prev == null) return false;
        return (System.currentTimeMillis() - prev) <= windowMs;
    }

    /**
     * Check if two users are already buddies (shortcut to avoid duplicate DB writes).
     */
    public static boolean alreadyBuddies(String userId, String friendId) {
        return new br.com.gunbound.emulator.model.DAO.impl.BuddyJDBC().isBuddy(userId, friendId);
    }
}
