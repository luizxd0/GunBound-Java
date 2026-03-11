package br.com.gunbound.emulator;

import br.com.gunbound.emulator.buddy.BuddyPacketDecoder;
import br.com.gunbound.emulator.handlers.BuddyServerHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * Netty server for the Buddy System, replacing BuddyServ2.exe.
 * Binds on port 8352 (configurable) and handles buddy protocol packets.
 * Runs in-process alongside GameServer and BrokerServer.
 */
public class GunBoundBuddyServer {

    private final int port;

    public GunBoundBuddyServer(int port) {
        this.port = port;
    }

    public void start() throws Exception {
        EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(0, NioIoHandler.newFactory());

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new LoggingHandler("BuddyServerLogger", LogLevel.INFO),
                                    // Buddy server uses 4-byte header (length + packetId)
                                    new BuddyPacketDecoder(),
                                    new BuddyServerHandler());
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            System.out.println("Buddy Server bound on port " + port);

            ChannelFuture f = b.bind(port).sync();

            f.channel().closeFuture().addListener(future -> {
                workerGroup.shutdownGracefully();
                bossGroup.shutdownGracefully();
            });

        } catch (Exception e) {
            System.err.println("Error starting Buddy Server: " + e.getMessage());
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
            throw e;
        }
    }
}
