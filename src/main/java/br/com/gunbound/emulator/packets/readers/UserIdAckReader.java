package br.com.gunbound.emulator.packets.readers;

import br.com.gunbound.emulator.buddy.BuddySession;
import br.com.gunbound.emulator.buddy.BuddySessionManager;
import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.model.entities.game.PlayerSessionManager;
import br.com.gunbound.emulator.packets.readers.buddy.BuddyAddReader;
import io.netty.channel.ChannelHandlerContext;

/**
 * Logs client-side 0x1021 payloads for interoperability debugging.
 * Some clients send additional profile/presence data under this opcode.
 */
public final class UserIdAckReader {
    private static final long BRIDGE_WINDOW_MS = 120_000L;

    private UserIdAckReader() {
    }

    public static void read(ChannelHandlerContext ctx, byte[] payload) {
        if (payload == null) {
            return;
        }

        // 0x1021 is also used for encrypted profile/presence chunks.
        // Only process ACK payloads when there is a recent 0x1020 user search.
        Long cachedTs = ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_TS).get();
        if (cachedTs == null) {
            return;
        }

        // Heuristic token extraction from fixed fields to speed up debugging.
        if (payload.length >= 14) {
            String token12 = fixedIdentifier(payload, 2, 12);
            if (token12.isEmpty() && payload.length >= 15) {
                token12 = fixedIdentifier(payload, 3, 12);
            }
            PlayerSession ps = token12.isEmpty()
                    ? null
                    : PlayerSessionManager.getInstance().getSessionPlayerByNickname(token12);
            if (!token12.isEmpty()) {
                System.out.println("SVC_USER_ID_ACK token12='" + token12 + "' mappedOnline=" + (ps != null));
            }
        }

        if (payload.length >= 18) {
            String token16 = fixedIdentifier(payload, 2, 16);
            if (token16.isEmpty() && payload.length >= 19) {
                token16 = fixedIdentifier(payload, 3, 16);
            }
            if (!token16.isEmpty()) {
                System.out.println("SVC_USER_ID_ACK token16='" + token16 + "'");
                maybeBridgeBuddyAdd(ctx, token16);
            }
        }
    }

    private static void maybeBridgeBuddyAdd(ChannelHandlerContext ctx, String token16) {
        PlayerSession requesterSession = ctx.channel().attr(GameAttributes.USER_SESSION).get();
        if (requesterSession == null || token16 == null || token16.isEmpty()) {
            return;
        }

        String cachedUserId = ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_USER_ID).get();
        String cachedNick = ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_NICK).get();
        Long cachedTs = ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_TS).get();
        if (cachedUserId == null || cachedTs == null) {
            return;
        }

        long ageMs = System.currentTimeMillis() - cachedTs;
        if (ageMs < 0 || ageMs > BRIDGE_WINDOW_MS) {
            clearSearchCache(ctx);
            return;
        }

        boolean matchesCached = token16.equalsIgnoreCase(cachedUserId)
                || (cachedNick != null && token16.equalsIgnoreCase(cachedNick));
        if (!matchesCached) {
            return;
        }

        if (cachedUserId.equalsIgnoreCase(requesterSession.getUserNameId())) {
            return; // ignore self-ack
        }

        BuddySession buddySession = BuddySessionManager.getInstance().getSession(requesterSession.getUserNameId());
        if (buddySession == null || !buddySession.isAuthenticated() || !buddySession.isActive()) {
            System.out.println("SVC_USER_ID_ACK bridge skipped: buddy session unavailable for "
                    + requesterSession.getUserNameId());
            return;
        }

        System.out.println("SVC_USER_ID_ACK bridge add: requester=" + requesterSession.getUserNameId()
                + " target=" + cachedUserId + " token=" + token16);
        BuddyAddReader.handleFromSession(buddySession, cachedUserId);
        clearSearchCache(ctx);
    }

    private static void clearSearchCache(ChannelHandlerContext ctx) {
        ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_USER_ID).set(null);
        ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_NICK).set(null);
        ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_TS).set(null);
    }

    private static String fixedIdentifier(byte[] payload, int offset, int maxLen) {
        int end = Math.min(payload.length, offset + maxLen);
        if (offset < 0 || offset >= end) {
            return "";
        }

        StringBuilder value = new StringBuilder(maxLen);
        for (int i = offset; i < end; i++) {
            int ub = payload[i] & 0xFF;
            if (ub == 0x00) {
                break;
            }

            if ((ub >= '0' && ub <= '9')
                    || (ub >= 'A' && ub <= 'Z')
                    || (ub >= 'a' && ub <= 'z')
                    || ub == '_'
                    || ub == '-') {
                value.append((char) ub);
                continue;
            }

            // Non-identifier byte in token window means this payload is not a user-id ACK.
            return "";
        }
        if (value.length() == 0) {
            return "";
        }
        return value.toString();
    }
}
