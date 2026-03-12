package br.com.gunbound.emulator.packets.readers.buddy;

import br.com.gunbound.emulator.buddy.BuddyConstants;
import br.com.gunbound.emulator.buddy.BuddyDecisionCache;
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

/**
 * Handles tunnel packets (0x2020) for chat, status updates, and buddy requests.
 * Parses the payload to find the target and relays as 0x2021 if online,
 * or saves to the database if offline.
 */
public class BuddyTunnelReader {

    public static void handle(ChannelHandlerContext ctx, byte[] payload) {
        BuddySession session = BuddySessionManager.getInstance().getSessionByChannel(ctx.channel());
        if (session == null || !session.isAuthenticated()) return;

        if (payload.length < 16) return;

        // Status relay packets do not include a valid target id; broadcast them.
        if (payload.length >= 29
                && payload[0] == 0x00
                && payload[1] == 0x51
                && payload[2] == (byte) 0xC0
                && payload[3] == 0x18) {
            ByteBuf relayPayload = Unpooled.buffer(56);
            String senderNick = session.getNickName() != null ? session.getNickName() : session.getUserId();

            relayPayload.writeBytes(BuddyPacketUtils.encFixed(session.getUserId(), 16));
            relayPayload.writeBytes(BuddyPacketUtils.encFixed(senderNick, 12));
            relayPayload.writeBytes(payload, 1, 28); // meta block

            BuddyDAO buddyDAO = new BuddyJDBC();
            for (Map<String, Object> b : buddyDAO.getBuddyList(session.getUserId())) {
                String friendId = (String) b.get("Buddy");
                if (friendId == null) continue;

                // Right after an accept, some clients can mis-handle the immediate
                // status relay (0x51 C0 ...) as a second confirmation popup.
                // Keep the accept popup path intact and suppress only this short-lived relay.
                if (BuddyDecisionCache.getInstance()
                        .isRecentDecision(session.getUserId(), friendId, true, 2500)) {
                    System.out.println("BS: Suppressed immediate status relay after accept: "
                            + session.getUserId() + " -> " + friendId);
                    continue;
                }

                BuddySession targetSession = BuddySessionManager.getInstance().getSession(friendId);
                if (targetSession != null && targetSession.isActive()) {
                    targetSession.getChannel().writeAndFlush(
                            BuddyPacketUtils.buildPacket(BuddyConstants.SVC_RELAY_BUDDY_REQ, relayPayload.copy())
                    );
                }
            }
            relayPayload.release();
            System.out.println("BS: Broadcast status relay from " + session.getUserId());
            return;
        }

        // In BuddyServ tunnel packets, the target id is usually the last 16 bytes (null-padded)
        byte[] targetChunk = new byte[16];
        System.arraycopy(payload, payload.length - 16, targetChunk, 0, 16);
        String targetNameRaw = BuddyPacketUtils.readFixedString(targetChunk, 0, 16);

        // The remaining payload (excluding the last 16 bytes)
        byte[] payloadData = new byte[payload.length - 16];
        System.arraycopy(payload, 0, payloadData, 0, payloadData.length);

        BuddyDAO buddyDAO = new BuddyJDBC();
        boolean broadcast = false;
        String targetUserId = null;
        if (!targetNameRaw.isEmpty() && !targetNameRaw.matches("^0+$")) {
            Map<String, Object> targetData = buddyDAO.getUserGameData(targetNameRaw);
            if (targetData == null) {
                targetData = buddyDAO.getUserByNickname(targetNameRaw);
            }

            if (targetData == null) {
                System.err.println("BS: Tunnel target not found: " + targetNameRaw);
                // For status relays, treat unknown/zero targets as broadcast
                if (payloadData.length >= 4 && payloadData[0] == 0x00) {
                    broadcast = true;
                } else {
                    return;
                }
            }
            if (targetData != null) {
                targetUserId = (String) targetData.get("UserId");
            }
        } else {
            broadcast = true;
        }

        String senderNick = session.getNickName() != null ? session.getNickName() : session.getUserId();

        ByteBuf relayPayload = Unpooled.buffer();
        boolean allowOffline = false;

        // Check format: Chat Relay (01 11 C0 <len> 00 <msg...> 01 00)
        if (payloadData.length >= 7 && payloadData[0] == 0x01 && payloadData[1] == 0x11 && payloadData[2] == (byte)0xC0) {
            int msgLen = payloadData[3] & 0xFF;
            int msgStart = (payloadData.length > 4 && payloadData[4] == 0x00) ? 5 : 4;
            int msgEnd = payloadData.length - 2; // skip 01 00
            
            int actualLen = Math.min(msgLen, msgEnd - msgStart);
            byte[] msgRaw = new byte[actualLen];
            System.arraycopy(payloadData, msgStart, msgRaw, 0, actualLen);
            
            byte[] msgField = new byte[40]; // 40 byte fixed chat field
            System.arraycopy(msgRaw, 0, msgField, 0, Math.min(msgRaw.length, 40));
            
            relayPayload.writeBytes(BuddyPacketUtils.encFixed(session.getUserId(), 16));
            relayPayload.writeBytes(BuddyPacketUtils.encFixed(senderNick, 12));
            relayPayload.writeBytes(new byte[]{0x11, (byte)0xC0, 0x1F, 0x00});
            relayPayload.writeBytes(msgField);
            
            allowOffline = true;
        }
        // Check format 3: Buddy Request via tunnel (01 41)
        else if (payloadData.length >= 2 && payloadData[0] == 0x01 && payloadData[1] == 0x41) {
            relayPayload.writeBytes(BuddyPacketUtils.encFixed(session.getUserId(), 16));
            relayPayload.writeBytes(BuddyPacketUtils.encFixed(senderNick, 12));
            relayPayload.writeBytes(BuddyConstants.BUDDY_INVITE_TAG);
            allowOffline = true;
        }
        else {
            // Detect tunnel-based accept/reject: 01 42 C0 01 00 XX
            if (payloadData.length >= 6 && payloadData[0] == 0x01 && payloadData[1] == 0x42 
                    && payloadData[2] == (byte)0xC0 && payloadData[3] == 0x01 && payloadData[4] == 0x00) {
                boolean isAccept = (payloadData[5] == 0x01);
                
                // Dedup: this is the tunnel version of 0x3000
                if (targetUserId != null && !BuddyDecisionCache.getInstance().shouldProcess(session.getUserId(), targetUserId, isAccept)) {
                    System.out.println("BS: [DEDUP] Suppressed duplicate tunnel " + (isAccept ? "accept" : "reject") + ": " + session.getUserId() + " -> " + targetUserId);
                    return;
                }
                
                if (isAccept && targetUserId != null) {
                    BuddyDAO dao = new BuddyJDBC();
                    if (!dao.isBuddy(session.getUserId(), targetUserId)) {
                        dao.addBuddy(session.getUserId(), targetUserId);
                        dao.addBuddy(targetUserId, session.getUserId());
                        System.out.println("BS: [TUNNEL] Mutual buddy added: " + session.getUserId() + " <-> " + targetUserId);
                    }
                    BuddyFriendListWriter.sendBuddyList(session);
                }
            }
            
            // Raw fallback for status relays, accept/reject popups, or arbitrary tunnel
            relayPayload.writeBytes(BuddyPacketUtils.encFixed(session.getUserId(), 16));
            relayPayload.writeBytes(BuddyPacketUtils.encFixed(senderNick, 12));
            relayPayload.writeBytes(payloadData);
        }

        // Deliver
        if (broadcast) {
            for (Map<String, Object> b : buddyDAO.getBuddyList(session.getUserId())) {
                String friendId = (String) b.get("Buddy");
                if (friendId == null) continue;
                BuddySession targetSession = BuddySessionManager.getInstance().getSession(friendId);
                if (targetSession != null && targetSession.isActive()) {
                    targetSession.getChannel().writeAndFlush(
                            BuddyPacketUtils.buildPacket(BuddyConstants.SVC_RELAY_BUDDY_REQ, relayPayload.copy())
                    );
                }
            }
            relayPayload.release();
            System.out.println("BS: Broadcast 0x2021 relay from " + session.getUserId());
        } else {
            BuddySession targetSession = BuddySessionManager.getInstance().getSession(targetUserId);
            if (targetSession != null && targetSession.isActive()) {
                targetSession.getChannel().writeAndFlush(BuddyPacketUtils.buildPacket(BuddyConstants.SVC_RELAY_BUDDY_REQ, relayPayload));
                BuddyFriendListWriter.sendSync(targetSession);
                System.out.println("BS: Tunneled 0x2021 relay from " + session.getUserId() + " to " + targetUserId);
            } else if (allowOffline) {
                byte[] offlineData = new byte[relayPayload.readableBytes()];
                relayPayload.getBytes(0, offlineData);
                buddyDAO.saveOfflinePacket(targetUserId, session.getUserId(), BuddyConstants.SVC_RELAY_BUDDY_REQ, offlineData);
                System.out.println("BS: Saved offline packet for " + targetUserId + " from " + session.getUserId());
            }
        }
    }
}
