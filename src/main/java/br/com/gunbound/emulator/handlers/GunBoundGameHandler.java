package br.com.gunbound.emulator.handlers;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

import br.com.gunbound.emulator.ConnectionManager;
import br.com.gunbound.emulator.lobby.GunBoundLobbyManager;
import br.com.gunbound.emulator.model.DAO.DAOFactory;
import br.com.gunbound.emulator.model.DAO.UserDAO;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.model.entities.game.PlayerSessionManager;
import br.com.gunbound.emulator.packets.OpcodeReaderFactory;
import br.com.gunbound.emulator.room.RoomManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class GunBoundGameHandler extends ChannelInboundHandlerAdapter {

    private static final int CURRENTUSER_OFFLINE = 0;

    // Gets the singleton instance of ConnectionManager
    private final ConnectionManager connectionManager = ConnectionManager.getInstance();

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Get the client's remote address
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();

        // Get IP and port
        String clientIp = remoteAddress.getAddress().getHostAddress();
        int clientPort = remoteAddress.getPort();

        System.out.println("GS: Cliente conectado! IP: " + clientIp + ", Porta: " + clientPort);

        // Initialize packet tx sum for this new connection.
        ctx.channel().attr(GameAttributes.PACKET_TX_SUM).set(0);

        // Initialize AUTH token attribute
        ctx.channel().attr(GameAttributes.AUTH_TOKEN).set(new byte[4]);

        // Initialize client version attribute
        ctx.channel().attr(GameAttributes.CLIENT_VERSION).set(0);

        // Register new connection
        Channel channel = ctx.channel();
        connectionManager.registerConnection(channel);

        System.out.println("GS: Total de conex\u00f5es ativas: " + connectionManager.getActiveConnectionCount());

        super.channelActive(ctx);
    }

    /**
     * Processes incoming packets from game clients.
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf in = null;

        try {
            if (!(msg instanceof ByteBuf)) {
                System.err.println("GS: Mensagem recebida n\u00e3o \u00e9 um ByteBuf. Tipo: " + msg.getClass().getName());
                ctx.fireChannelRead(msg);
                return;
            }

            in = (ByteBuf) msg;

            // Ensure packet has at least sequence + command
            if (in.readableBytes() < 4) {
                System.err.println("GS: Pacote de jogo inv\u00e1lido (tamanho < 4 ap\u00f3s decodifica\u00e7\u00e3o)");
                return;
            }

            // 1) Skip packet sequence (2 bytes)
            in.skipBytes(2);

            // 2) Read command (2 bytes little-endian)
            int command = in.readUnsignedShortLE();

            // 3) Remaining bytes are payload
            byte[] payloadData = new byte[in.readableBytes()];
            in.readBytes(payloadData);

            BiConsumer<ChannelHandlerContext, byte[]> reader = OpcodeReaderFactory.getReader(command);
            if (reader != null) {
                reader.accept(ctx, payloadData);
            } else {
                System.err.println("GS: Comando 0x" + Integer.toHexString(command) + " desconhecido no Game Server.");
            }

        } finally {
            // Manually release ByteBuf
            if (in != null) {
                in.release();
            }
        }
    }

    /**
     * Called when the game client disconnects.
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        connectionManager.removeConnection(ctx.channel());
        System.err.println("GS: Cliente de jogo desconectado de " + ctx.channel().remoteAddress());

        PlayerSession ps = PlayerSessionManager.getInstance().getPlayer(ctx.channel());
        PlayerSessionManager.getInstance().removePlayer(ctx.channel());

        ctx.close();

        if (ps != null) {
            if (!hasAnotherSessionForUser(ps.getUserNameId(), ctx.channel())) {
                updateCurrentUserPresence(ps, ctx, CURRENTUSER_OFFLINE);
            }

            GunBoundLobbyManager.getInstance().playerLeaveLobby(ps);
            RoomManager.getInstance().handlePlayerLeave(ps);

            if (ps.getNickName() == null) {
                System.err.println("GS: Player desconhecido desconectado");
            } else {
                System.err.println("GS: Player " + ps.getNickName() + " desconectado");
            }
        }
    }

    /**
     * Called when an exception occurs in the pipeline.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("GS: Exce\u00e7\u00e3o no Game Server para " + ctx.channel().remoteAddress() + ":");
        PlayerSession ps = PlayerSessionManager.getInstance().getPlayer(ctx.channel());

        GunBoundLobbyManager.getInstance().playerLeaveLobby(ps);
        RoomManager.getInstance().handlePlayerLeave(ps);
        connectionManager.removeConnection(ctx.channel());

        PlayerSessionManager.getInstance().removePlayer(ctx.channel());
        if (ps != null && !hasAnotherSessionForUser(ps.getUserNameId(), ctx.channel())) {
            updateCurrentUserPresence(ps, ctx, CURRENTUSER_OFFLINE);
        }

        if (ps == null || ps.getNickName() == null) {
            System.err.println("GS: Exce\u00e7\u00e3o no Game Server para o Player desconhecido");
        } else {
            System.err.println("GS: Exce\u00e7\u00e3o no Game Server para o Player " + ps.getNickName());
        }
        cause.printStackTrace();

        ctx.close();
    }

    private static boolean hasAnotherSessionForUser(String userId, Channel disconnectedChannel) {
        if (userId == null || userId.isBlank()) {
            return false;
        }

        for (PlayerSession session : PlayerSessionManager.getInstance().getAllPlayers()) {
            if (session == null || session.getUserNameId() == null) {
                continue;
            }
            if (!session.getUserNameId().equalsIgnoreCase(userId)) {
                continue;
            }

            Channel channel = session.getPlayerCtxChannel();
            if (channel != null && channel != disconnectedChannel && channel.isActive()) {
                return true;
            }
        }

        return false;
    }

    private static void updateCurrentUserPresence(PlayerSession session, ChannelHandlerContext ctx, int context) {
        if (session == null || session.getUserNameId() == null || session.getUserNameId().isBlank()) {
            return;
        }

        String serverIp = "127.0.0.1";
        int serverPort = 0;

        if (ctx != null && ctx.channel() != null && ctx.channel().localAddress() instanceof InetSocketAddress) {
            InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
            if (local.getAddress() != null) {
                serverIp = local.getAddress().getHostAddress();
            }
            serverPort = local.getPort();
        }

        try (UserDAO dao = DAOFactory.CreateUserDao()) {
            dao.upsertCurrentUser(session.getUserNameId(), context, serverIp, serverPort);
        } catch (Exception e) {
            System.err.println("GS: Falha ao atualizar currentuser para " + session.getUserNameId() + ": "
                    + e.getMessage());
        }
    }
}
