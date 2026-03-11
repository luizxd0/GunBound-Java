package br.com.gunbound.emulator.buddy;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * Minimal UDP echo used by BuddyServ2 for context/keepalive.
 * Echoes the 4-byte payload back to the sender.
 */
public class BuddyUdpServer implements Runnable {
    private final int port;
    private volatile boolean running = true;
    private DatagramSocket socket;

    public BuddyUdpServer(int port) {
        this.port = port;
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(new InetSocketAddress("0.0.0.0", port));
            byte[] buf = new byte[64];
            while (running) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);
                int len = pkt.getLength();
                if (len <= 0) continue;

                byte[] echo = new byte[len];
                System.arraycopy(pkt.getData(), pkt.getOffset(), echo, 0, len);
                DatagramPacket out = new DatagramPacket(echo, echo.length, pkt.getAddress(), pkt.getPort());
                socket.send(out);

                // Check if this is a 4-byte nonce handshake from the client
                if (len == 4) {
                    int nonce = java.nio.ByteBuffer.wrap(echo).getInt();
                    BuddySession session = BuddySessionManager.getInstance().getSessionByNonce(nonce);
                    if (session != null && session.isActive()) {
                        System.out.println("BS UDP: Received valid nonce from " + pkt.getAddress().getHostAddress() + ":" + pkt.getPort());
                        
                        // The client proved it can reach us via UDP. 
                        // Record its public STUN IP and Port
                        session.setClientExternalIp(pkt.getAddress().getAddress());
                        session.setClientExternalPort(pkt.getPort());
                        
                        // Send 0x101F UDP Context back via TCP so the client knows its P2P address
                        io.netty.buffer.ByteBuf udpContextPayload = io.netty.buffer.Unpooled.buffer(6);
                        udpContextPayload.writeBytes(session.getClientExternalIp());
                        // Write the client's external UDP port (Big Endian)
                        udpContextPayload.writeShort(session.getClientExternalPort());
                        
                        session.getChannel().writeAndFlush(
                                br.com.gunbound.emulator.buddy.BuddyPacketUtils.buildPacket(BuddyConstants.SVC_UDP_CONTEXT, udpContextPayload));

                        // Finalize the Login Handshake (Sends 0x3FFF self-sync, broadcasts status, and processes offline packets)
                        br.com.gunbound.emulator.packets.writers.buddy.BuddyFriendListWriter.finalizeLoginHandshake(session);
                    }
                }
            }
        } catch (Exception e) {
            if (running) {
                System.err.println("BS UDP: " + e.getMessage());
            }
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }
}
