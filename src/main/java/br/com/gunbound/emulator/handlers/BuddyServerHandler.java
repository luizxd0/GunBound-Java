package br.com.gunbound.emulator.handlers;

import br.com.gunbound.emulator.buddy.BuddyConstants;
import br.com.gunbound.emulator.buddy.BuddySession;
import br.com.gunbound.emulator.buddy.BuddySessionManager;
import br.com.gunbound.emulator.packets.readers.buddy.*;
import br.com.gunbound.emulator.packets.writers.buddy.BuddyFriendListWriter;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.net.InetSocketAddress;

/**
 * Netty handler for the Buddy Server.
 * Dispatches buddy protocol opcodes to appropriate reader classes.
 * Replaces BuddyServ2.exe's packet dispatch loop.
 */
public class BuddyServerHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String clientIp = remoteAddress.getAddress().getHostAddress();
        int clientPort = remoteAddress.getPort();
        System.out.println("BS: Client connected! IP: " + clientIp + ", Port: " + clientPort);

        // Create a new BuddySession for this connection
        BuddySession session = new BuddySession(ctx.channel());
        // Store session reference in channel attribute for later retrieval
        BuddySessionManager.getInstance();

        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf in = null;

        try {
            if (!(msg instanceof ByteBuf)) {
                System.err.println("BS: Received non-ByteBuf message: " + msg.getClass().getName());
                ctx.fireChannelRead(msg);
                return;
            }

            in = (ByteBuf) msg;

            // BuddyServ packet: after decoder strips length (2 bytes),
            // remaining is: packetId(2LE) + payload
            if (in.readableBytes() < 2) {
                System.err.println("BS: Buddy packet too small (< 2 bytes after length)");
                return;
            }

            int packetId = in.readUnsignedShortLE();
            byte[] payload = new byte[in.readableBytes()];
            in.readBytes(payload);

            // Dispatch to handlers based on opcode
            switch (packetId) {
                case BuddyConstants.SVC_LOGIN_REQ:
                    // Initial login handshake - respond with auth token
                    BuddyLoginReader.handleLoginReq(ctx, payload);
                    break;

                case BuddyConstants.SVC_LOGIN_DATA:
                    // Login with encrypted userId
                    BuddyLoginReader.handleLoginData(ctx, payload);
                    break;

                case BuddyConstants.SVC_BUDDY_LOGIN:
                    // Alternate login path (version + userId)
                    BuddyLoginReader.handleBuddyLogin(ctx, payload);
                    break;

                case BuddyConstants.SVC_ADD_BUDDY:
                    BuddyAddReader.handle(ctx, payload);
                    break;

                case BuddyConstants.SVC_REMOVE_BUDDY:
                    BuddyRemoveReader.handle(ctx, payload);
                    break;

                case BuddyConstants.BUDDY_ACCEPT_REJECT:
                    BuddyAcceptRejectReader.handle(ctx, payload);
                    break;

                case BuddyConstants.SVC_USER_STATE:
                    BuddyUserStateReader.handle(ctx, payload);
                    break;

                case BuddyConstants.SVC_SEARCH:
                    BuddySearchReader.handle(ctx, payload);
                    break;

                case BuddyConstants.SVC_TUNNEL_PACKET:
                    BuddyTunnelReader.handle(ctx, payload);
                    break;

                case BuddyConstants.SVC_SAVE_PACKET:
                    BuddySavePacketReader.handle(ctx, payload);
                    break;

                case BuddyConstants.SVC_DELETE_PACKET:
                    BuddyDeletePacketReader.handle(ctx, payload);
                    break;

                default:
                    System.err.println("BS: Unknown opcode 0x" + Integer.toHexString(packetId)
                            + " from " + ctx.channel().remoteAddress());
                    break;
            }

        } finally {
            if (in != null) {
                in.release();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        BuddySession session = BuddySessionManager.getInstance().removeSession(ctx.channel());

        if (session != null && session.getUserId() != null) {
            System.out.println("BS: User " + session.getUserId() + " disconnected");

            // Notify online buddies that this user went offline
            BuddyFriendListWriter.notifyBuddiesOfStateChange(session, false);
        } else {
            System.out.println("BS: Unauthenticated client disconnected from "
                    + ctx.channel().remoteAddress());
        }

        ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("BS: Exception for " + ctx.channel().remoteAddress() + ": " + cause.getMessage());
        cause.printStackTrace();

        BuddySession session = BuddySessionManager.getInstance().removeSession(ctx.channel());
        if (session != null && session.getUserId() != null) {
            BuddyFriendListWriter.notifyBuddiesOfStateChange(session, false);
        }

        ctx.close();
    }
}
