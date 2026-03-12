package br.com.gunbound.emulator.packets.readers.buddy;

import br.com.gunbound.emulator.buddy.BuddyConstants;
import br.com.gunbound.emulator.buddy.BuddyPacketUtils;
import br.com.gunbound.emulator.buddy.BuddySession;
import br.com.gunbound.emulator.buddy.BuddySessionManager;
import br.com.gunbound.emulator.model.DAO.BuddyDAO;
import br.com.gunbound.emulator.model.DAO.impl.BuddyJDBC;
import br.com.gunbound.emulator.packets.writers.buddy.BuddyFriendListWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles adding a buddy (0x3010).
 */
public class BuddyAddReader {

    private static final int ADD_RESULT_OK = 0x0010;
    private static final int ADD_RESULT_ALREADY = 0x0051;
    private static final int ADD_RESULT_FAIL = 0x0000;
    private static final long PENDING_TTL_MS = 10 * 60 * 1000L;
    private static final ConcurrentHashMap<String, Long> PENDING_INVITES = new ConcurrentHashMap<>();

    public static void handle(ChannelHandlerContext ctx, byte[] payload) {
        BuddySession session = BuddySessionManager.getInstance().getSessionByChannel(ctx.channel());
        if (session == null || !session.isAuthenticated()) return;

        // Payload contains friend nickname (16 bytes, null-padded)
        String friendNick = BuddyPacketUtils.readFixedString(payload, 0, 16);
        handleFromSession(session, friendNick, "tcp3010");
    }

    public static void handleFromSession(BuddySession session, String friendNick) {
        handleFromSession(session, friendNick, "bridge1021");
    }

    private static void handleFromSession(BuddySession session, String friendNick, String source) {
        if (session == null || !session.isAuthenticated()) return;
        if (friendNick == null) {
            friendNick = "";
        }
        System.out.println("BS: SVC_ADD_BUDDY (" + source + ") from " + session.getUserId()
                + " target='" + friendNick + "'");
        if (friendNick.isEmpty()) {
            sendAddResponse(session, ADD_RESULT_FAIL); // Fail
            return;
        }

        BuddyDAO buddyDAO = new BuddyJDBC();
        
        // Resolve target user mapping NickName -> UserId
        Map<String, Object> friendData = buddyDAO.getUserByNickname(friendNick);
        if (friendData == null) {
            friendData = buddyDAO.getUserGameData(friendNick); // Fallback lookup by UserId
        }

        if (friendData == null) {
            System.err.println("BS: Add Buddy failed - User not found: " + friendNick);
            // Client-side P2P_RET_ADDBUDDY popup path (flag=0 => not accepted/not found branch).
            sendRetAddBuddyFailPopup(session, friendNick);
            sendAddResponse(session, ADD_RESULT_FAIL);
            return;
        }

        String targetUserId = (String) friendData.get("UserId");

        if (buddyDAO.isBuddy(session.getUserId(), targetUserId)) {
            System.out.println("BS: " + session.getUserId() + " and " + targetUserId + " are already buddies. Skipping add.");
            sendAddResponse(session, ADD_RESULT_ALREADY);
            return;
        }

        if (targetUserId.equalsIgnoreCase(session.getUserId())) {
            System.err.println("BS: Self-add blocked for " + session.getUserId());
            sendAddResponse(session, ADD_RESULT_FAIL);
            return;
        }

        if (isPending(session.getUserId(), targetUserId) || isPending(targetUserId, session.getUserId())) {
            System.out.println("BS: Pending buddy invite exists for " + session.getUserId() + " <-> " + targetUserId);
            sendAddResponse(session, ADD_RESULT_ALREADY);
            return;
        }

        System.out.println("BS: Relaying buddy request from " + session.getUserId() + " to " + targetUserId);

        // Send 0x2021 Relay Buddy Invitation to Target
        // Relay payload (41b): UID(16) + Nick(12) + Tag(13)
        ByteBuf relay = Unpooled.buffer(41);
        String senderNick = session.getNickName() != null ? session.getNickName() : session.getUserId();

        relay.writeBytes(BuddyPacketUtils.encFixed(session.getUserId(), 16));
        relay.writeBytes(BuddyPacketUtils.encFixed(senderNick, 12));
        relay.writeBytes(BuddyConstants.BUDDY_INVITE_TAG);

        byte[] relayBody = new byte[relay.readableBytes()];
        relay.getBytes(0, relayBody);

        BuddySession targetSession = BuddySessionManager.getInstance().getSession(targetUserId);
        boolean targetOnline = targetSession != null
                && targetSession.isActive()
                && targetSession.isAuthenticated()
                && targetSession.isLoginHandshakeFinalized();

        if (targetOnline) {
            ByteBuf packet = BuddyPacketUtils.buildPacket(BuddyConstants.SVC_RELAY_BUDDY_REQ, relay);
            relay.release();
            targetSession.getChannel().writeAndFlush(packet
            ).addListener(future -> {
                if (!future.isSuccess()) {
                    boolean saved = buddyDAO.saveOfflinePacket(
                            targetUserId,
                            session.getUserId(),
                            BuddyConstants.SVC_RELAY_BUDDY_REQ,
                            relayBody
                    );
                    if (saved) {
                        System.out.println("BS: Saved offline buddy invite after relay failure: "
                                + session.getUserId() + " -> " + targetUserId);
                    } else {
                        System.err.println("BS: Failed to save offline buddy invite after relay failure: "
                                + session.getUserId() + " -> " + targetUserId);
                    }
                }
            });
        } else {
            boolean saved = buddyDAO.saveOfflinePacket(
                    targetUserId,
                    session.getUserId(),
                    BuddyConstants.SVC_RELAY_BUDDY_REQ,
                    relayBody
            );
            if (saved) {
                System.out.println("BS: Saved offline buddy invite: " + session.getUserId() + " -> " + targetUserId);
            } else {
                System.err.println("BS: Failed to save offline buddy invite: " + session.getUserId() + " -> " + targetUserId);
            }
            relay.release();
        }

        markPending(session.getUserId(), targetUserId);

        // Send success to sender
        sendAddResponse(session, ADD_RESULT_OK);
        // If target is truly offline, push an explicit offline sync to reduce
        // client-side P2P fallback delay for subsequent chat/invite actions.
        if (targetSession == null || !targetSession.isActive() || !targetSession.isAuthenticated()) {
            BuddyFriendListWriter.sendOfflineSync(session, targetUserId);
        }
        BuddyFriendListWriter.sendSync(session);
    }

    private static void sendAddResponse(BuddySession session, int result) {
        ByteBuf resp = Unpooled.buffer(2);
        resp.writeShortLE(result);
        session.getChannel().writeAndFlush(BuddyPacketUtils.buildPacket(BuddyConstants.SVC_ADD_BUDDY_RESP, resp));
    }

    private static void sendRetAddBuddyFailPopup(BuddySession session, String targetName) {
        String safeTarget = targetName != null ? targetName : "";
        // Gunbound.gme P2P_RET_ADDBUDDY expects: status(1) + id/nick payload.
        // status=0 triggers the fail/not-found popup branch.
        ByteBuf popup = Unpooled.buffer(17);
        popup.writeByte(0x00);
        popup.writeBytes(BuddyPacketUtils.encFixed(safeTarget, 16));
        session.getChannel().writeAndFlush(BuddyPacketUtils.buildPacket(BuddyConstants.SVC_RELAY_BUDDY_REQ, popup));
    }

    static void clearPending(String senderId, String targetUserId) {
        if (senderId == null || targetUserId == null) return;
        PENDING_INVITES.remove(pendingKey(senderId, targetUserId));
    }

    private static void markPending(String senderId, String targetUserId) {
        PENDING_INVITES.put(pendingKey(senderId, targetUserId), System.currentTimeMillis());
    }

    static void registerPending(String senderId, String targetUserId) {
        if (senderId == null || targetUserId == null) return;
        markPending(senderId, targetUserId);
    }

    private static boolean isPending(String senderId, String targetUserId) {
        String key = pendingKey(senderId, targetUserId);
        Long ts = PENDING_INVITES.get(key);
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts > PENDING_TTL_MS) {
            PENDING_INVITES.remove(key);
            return false;
        }
        return true;
    }

    static boolean hasPending(String senderId, String targetUserId) {
        return isPending(senderId, targetUserId);
    }

    private static String pendingKey(String senderId, String targetUserId) {
        return senderId.toLowerCase() + "->" + targetUserId.toLowerCase();
    }
}
