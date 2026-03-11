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

/**
 * Handles removing a buddy (0x3002).
 */
public class BuddyRemoveReader {

    public static void handle(ChannelHandlerContext ctx, byte[] payload) {
        BuddySession session = BuddySessionManager.getInstance().getSessionByChannel(ctx.channel());
        if (session == null || !session.isAuthenticated()) return;

        // Payload contains friend id (16 bytes, null-padded)
        String friendId = BuddyPacketUtils.readFixedString(payload, 0, 16);

        if (friendId.isEmpty()) {
            sendRemoveResponse(ctx, 0, ""); // Fail
            return;
        }

        BuddyDAO buddyDAO = new BuddyJDBC();
        boolean removedFromA = buddyDAO.removeBuddy(session.getUserId(), friendId);
        boolean removedFromB = buddyDAO.removeBuddy(friendId, session.getUserId());
        
        boolean success = removedFromA || removedFromB;

        if (success) {
            System.out.println("BS: Mutual buddy removed: " + session.getUserId() + " <-> " + friendId);
            sendRemoveResponse(ctx, 1, friendId);
        } else {
            sendRemoveResponse(ctx, 0, "");
        }

        // Refresh sender friend list
        BuddyFriendListWriter.sendBuddyList(session);

        // If target is online, send live removal packet and refresh their list
        BuddySession targetSession = BuddySessionManager.getInstance().getSession(friendId);
        if (targetSession != null && success) {
            sendLiveRemovePopup(targetSession, session.getUserId(), session.getNickName());
            BuddyFriendListWriter.sendBuddyList(targetSession);
        }
    }

    private static void sendRemoveResponse(ChannelHandlerContext ctx, int result, String friendId) {
        ByteBuf resp = Unpooled.buffer();
        resp.writeIntLE(result);
        if (result == 1) {
            resp.writeBytes(BuddyPacketUtils.encFixed(friendId, friendId.length() + 1));
        }
        ctx.writeAndFlush(BuddyPacketUtils.buildPacket(BuddyConstants.SVC_REMOVE_BUDDY_RESP, resp));
    }

    private static void sendLiveRemovePopup(BuddySession targetSession, String actorUserId, String actorNick) {
        // Layout: UID(16) + Nick(12) + Marker(5: 43 C0 01 00 01)
        ByteBuf p = Unpooled.buffer(33);
        p.writeBytes(BuddyPacketUtils.encFixed(actorUserId, 16));
        p.writeBytes(BuddyPacketUtils.encFixed(actorNick != null ? actorNick : actorUserId, 12));
        p.writeBytes(new byte[]{0x43, (byte)0xC0, 0x01, 0x00, 0x01});
        targetSession.getChannel().writeAndFlush(BuddyPacketUtils.buildPacket(BuddyConstants.SVC_RELAY_BUDDY_REQ, p));
    }
}
