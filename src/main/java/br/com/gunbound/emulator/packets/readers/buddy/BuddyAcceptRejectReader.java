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
 * Handles accept/reject of buddy requests (0x3000).
 */
public class BuddyAcceptRejectReader {

    public static void handle(ChannelHandlerContext ctx, byte[] payload) {
        BuddySession session = BuddySessionManager.getInstance().getSessionByChannel(ctx.channel());
        if (session == null || !session.isAuthenticated()) return;

        if (payload.length < 17) return;

        // Log the payload for debugging "Ambiguous action"
        StringBuilder hexPayload = new StringBuilder();
        for (byte b : payload) hexPayload.append(String.format("%02X ", b));
        System.out.println("BS: Buddy Accept/Reject Payload (size " + payload.length + "): " + hexPayload.toString());

        // User ID or NickName of the person who sent the request
        String targetName = BuddyPacketUtils.readFixedString(payload, 0, 16);

        // Find the action byte (0x02 = Accept, 0x03 = Reject)
        // Historically at payload[16] or slightly after
        int action = -1;
        if (payload[16] == 0x02 || payload[16] == 0x03) {
            action = payload[16];
        } else {
            // Search for 0x02/0x03 within a reasonable range
            for (int i = 16; i < Math.min(payload.length, 32); i++) {
                if (payload[i] == 0x02 || payload[i] == 0x03) {
                    action = payload[i];
                    break;
                }
            }
        }

        // Fallback marker search for confirmation bytes (42 C0 01 00 XX)
        if (action == -1) {
            byte[] marker = {0x42, (byte)0xC0, 0x01, 0x00};
            for (int i = 16; i < payload.length - 4; i++) {
                if (payload[i] == marker[0] && payload[i+1] == marker[1] &&
                    payload[i+2] == marker[2] && payload[i+3] == marker[3]) {
                    byte flag = payload[i+4];
                    if (flag == 0x01) action = 0x02; // Map to Accept
                    else if (flag == 0x00) action = 0x03; // Map to Reject
                    break;
                }
            }
        }

        // Newer client payloads (e.g. 36 bytes) sometimes encode accept/reject
        // as a little-endian 32-bit flag at offset 20 (1 = accept, 0 = reject).
        if (action == -1 && payload.length >= 24) {
            int flag = (payload[20] & 0xFF)
                    | ((payload[21] & 0xFF) << 8)
                    | ((payload[22] & 0xFF) << 16)
                    | ((payload[23] & 0xFF) << 24);
            if (flag == 1) {
                action = 0x02;
            } else if (flag == 0) {
                action = 0x03;
            }
        }

        // Minimal fallback: single-byte flag at offset 20
        if (action == -1 && payload.length >= 21) {
            if (payload[20] == 0x01) {
                action = 0x02;
            } else if (payload[20] == 0x00) {
                action = 0x03;
            }
        }

        if (action == -1) {
            System.err.println("BS: Ambiguous action (0x02/0x03 not found) in buddy accept/reject from " + session.getUserId());
            return;
        }

        boolean isAccept = (action == 0x02);
        
        BuddyDAO buddyDAO = new BuddyJDBC();
        Map<String, Object> targetData = buddyDAO.getUserGameData(targetName);
        if (targetData == null) {
            targetData = buddyDAO.getUserByNickname(targetName);
        }

        if (targetData == null) {
            System.err.println("BS: Target not found for accept/reject: " + targetName);
            return;
        }

        String targetUserId = (String) targetData.get("UserId");

        // Dedup: client sends both 0x3000 and 0x2020 for the same action
        if (!BuddyDecisionCache.getInstance().shouldProcess(session.getUserId(), targetUserId, isAccept)) {
            System.out.println("BS: [DEDUP] Suppressed duplicate " + (isAccept ? "accept" : "reject") + ": " + session.getUserId() + " -> " + targetUserId);
            return;
        }

        if (isAccept) {
            // Skip if already buddies (idempotent)
            if (!buddyDAO.isBuddy(session.getUserId(), targetUserId)) {
                buddyDAO.addBuddy(session.getUserId(), targetUserId);
                buddyDAO.addBuddy(targetUserId, session.getUserId());
                System.out.println("BS: Mutual buddy added: " + session.getUserId() + " <-> " + targetUserId);
            } else {
                System.out.println("BS: Already buddies, skipping DB: " + session.getUserId() + " <-> " + targetUserId);
            }

            // Refresh for Recipient (accepter)
            BuddyFriendListWriter.sendBuddyList(session);
        }

        BuddyAddReader.clearPending(session.getUserId(), targetUserId);
        BuddyAddReader.clearPending(targetUserId, session.getUserId());

        // Notify Sender (requester)
        BuddySession senderSession = BuddySessionManager.getInstance().getSession(targetUserId);
        if (senderSession != null) {
            String myNick = session.getNickName() != null ? session.getNickName() : session.getUserId();

            // Rejection popup still uses explicit relay marker.
            // Accept popup is intentionally not relayed here because some clients
            // already raise the same confirmation from the subsequent list/sync updates.
            if (!isAccept) {
                byte[] marker = new byte[]{0x42, (byte)0xC0, 0x01, 0x00, 0x00};
                ByteBuf popup = Unpooled.buffer();
                popup.writeBytes(BuddyPacketUtils.encFixed(session.getUserId(), 16));
                popup.writeBytes(BuddyPacketUtils.encFixed(myNick, 12));
                popup.writeBytes(marker);
                senderSession.getChannel().writeAndFlush(BuddyPacketUtils.buildPacket(BuddyConstants.SVC_RELAY_BUDDY_REQ, popup));
            }

            if (isAccept) {
                // Refresh the buddy list for the sender
                BuddyFriendListWriter.sendBuddyList(senderSession);
                
                // Immediately synchronize statuses so both clients get each other's IPs (P2P handshakes)
                BuddyFriendListWriter.sendSync(session, senderSession, true); // Send Sender's IP to Receiver
                BuddyFriendListWriter.sendSync(senderSession, session, true); // Send Receiver's IP to Sender
            }
        }
        
        // Sync is handled by sendBuddyList or wasn't an accept
    }
}
