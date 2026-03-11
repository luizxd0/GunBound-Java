package br.com.gunbound.emulator.packets.readers.buddy;

import br.com.gunbound.emulator.buddy.BuddySession;
import br.com.gunbound.emulator.buddy.BuddySessionManager;
import br.com.gunbound.emulator.model.DAO.BuddyDAO;
import br.com.gunbound.emulator.model.DAO.impl.BuddyJDBC;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

/**
 * Handles deleting an offline packet (0x2011).
 */
public class BuddyDeletePacketReader {

    public static void handle(ChannelHandlerContext ctx, byte[] payload) {
        BuddySession session = BuddySessionManager.getInstance().getSessionByChannel(ctx.channel());
        if (session == null || !session.isAuthenticated()) return;

        // Standard size for deleting packet might carry a SerialNo
        if (payload.length >= 8) {
             ByteBuf in = Unpooled.wrappedBuffer(payload);
             long serialNo = in.readLongLE();
             
             BuddyDAO buddyDAO = new BuddyJDBC();
             buddyDAO.deleteOfflinePacket(serialNo);
        } else {
             // Fallback: If no direct serial sent or format differs, we might just wipe the whole offline box if needed
             // But usually it's targeted. For now, do nothing if we can't parse SerialNo.
             System.err.println("BS: Missing SerialNo in 0x2011 delete packet request.");
        }
    }
}
