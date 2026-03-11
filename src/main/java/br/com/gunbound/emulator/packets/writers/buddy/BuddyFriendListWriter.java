package br.com.gunbound.emulator.packets.writers.buddy;

import br.com.gunbound.emulator.buddy.BuddyConstants;
import br.com.gunbound.emulator.buddy.BuddyPacketUtils;
import br.com.gunbound.emulator.buddy.BuddySession;
import br.com.gunbound.emulator.buddy.BuddySessionManager;
import br.com.gunbound.emulator.model.DAO.BuddyDAO;
import br.com.gunbound.emulator.model.DAO.impl.BuddyJDBC;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.List;
import java.util.Map;

/**
 * Handles sending buddy list (0x1011) and related status updates.
 */
public class BuddyFriendListWriter {

    /**
     * Sends the complete buddy list to a client.
     * Contains 0x1011 (Buddy List), 0x3001 (Add Buddy Resp / Info), and 0x2021 (Relay Status).
     * Ends with 0x3FFF (Sync).
     */
    public static void sendBuddyList(BuddySession session) {
        if (session.getUserId() == null) return;

        BuddyDAO buddyDAO = new BuddyJDBC();
        List<Map<String, Object>> buddies = buddyDAO.getBuddyList(session.getUserId());

        // Generate 4-byte UDP nonce
        byte[] nonce = new byte[4];
        new java.security.SecureRandom().nextBytes(nonce);
        int nonceInt = java.nio.ByteBuffer.wrap(nonce).getInt();
        session.setUdpNonce(nonceInt);
        BuddySessionManager.getInstance().registerNonce(nonceInt, session);

        // Self info
        String selfNick = session.getNickName() != null ? session.getNickName() : session.getUserId();
        String selfGuild = session.getGuild() != null ? session.getGuild() : "";
        int selfGrade = session.getTotalGrade();

        ByteBuf listPayload = Unpooled.buffer(28 + (buddies.size() * 41));
        listPayload.writeShortLE(0); // Prefix
        listPayload.writeBytes(nonce); // UDP Nonce
        listPayload.writeBytes(BuddyPacketUtils.encFixed(selfNick, 12));
        listPayload.writeBytes(BuddyPacketUtils.encFixed(selfGuild, 8));
        listPayload.writeShortLE(selfGrade);

        for (Map<String, Object> b : buddies) {
            String friendId = (String) b.get("Buddy");
            if (friendId == null) continue;

            String nickName = (String) b.get("NickName");
            if (nickName == null) nickName = friendId;
            String guild = (String) b.get("Guild");
            if (guild == null) guild = "";
            int totalGrade = b.get("TotalGrade") != null ? (Integer) b.get("TotalGrade") : 0;

            // In 0x1011, status is always 0x00 for the buddy list payload.
            // Actual online status is sent afterwards via 0x3FFF User Sync Broadcast.
            // If we send 0x01 here, the client misaligns the byte offset and breaks the total grade (level) rendering.
            byte status = (byte) 0x00;

            listPayload.writeShortLE(1); // Group ID
            listPayload.writeBytes(BuddyPacketUtils.encFixed(friendId, 16));
            listPayload.writeBytes(BuddyPacketUtils.encFixed(nickName, 12));
            listPayload.writeByte(status);
            listPayload.writeBytes(BuddyPacketUtils.encFixed(guild, 8));
            listPayload.writeShortLE(totalGrade);
        }

        session.getChannel().writeAndFlush(BuddyPacketUtils.buildPacket(BuddyConstants.SVC_BUDDY_LIST, listPayload));
    }

    /**
     * Sends the 0x3FFF Sync packet marking end of buddy list or status update.
     */
    /**
     * Sends the 0x3FFF User Sync packet.
     * @param target The session receiving the packet.
     * @param subject The session whose status is being broadcast.
     * @param online True if the subject is online, false if offline.
     */
    public static void sendSync(BuddySession target, BuddySession subject, boolean online) {
        if (target == null || !target.isActive() || subject == null) return;

        byte[] tail = BuddyPacketUtils.buildSyncTail(subject, online);
        ByteBuf sync = Unpooled.buffer(2 + 16 + tail.length);
        sync.writeShortLE(0x0100);
        sync.writeBytes(BuddyPacketUtils.encFixed(subject.getUserId(), 16));
        sync.writeBytes(tail);
        
        target.getChannel().writeAndFlush(BuddyPacketUtils.buildPacket(BuddyConstants.SVC_USER_SYNC, sync));
    }

    /**
     * Notifies all online buddies that this user has logged in or out.
     */
    public static void notifyBuddiesOfStateChange(BuddySession session, boolean online) {
        if (session.getUserId() == null) return;
        
        BuddyDAO buddyDAO = new BuddyJDBC();
        List<Map<String, Object>> buddies = buddyDAO.getBuddyList(session.getUserId());
        
        for (Map<String, Object> b : buddies) {
            String friendId = (String) b.get("Buddy");
            if (friendId == null) continue;

            BuddySession friendSession = BuddySessionManager.getInstance().getSession(friendId);
            if (friendSession != null && friendSession.isActive()) {
                // To the friend: The session entered/left
                sendSync(friendSession, session, online);
                
                // If it's a login (online = true), tell the session that the friend is online
                if (online) {
                    sendSync(session, friendSession, true);
                }
            }
        }
    }

    public static void finalizeLoginHandshake(BuddySession session) {
        // 1. Notify Buddies of State Change (and exchange statuses)
        notifyBuddiesOfStateChange(session, true);
        // 2. Process Offline Packets
        br.com.gunbound.emulator.packets.readers.buddy.BuddyLoginReader.processOfflinePackets(session, new BuddyJDBC());
    }
}
