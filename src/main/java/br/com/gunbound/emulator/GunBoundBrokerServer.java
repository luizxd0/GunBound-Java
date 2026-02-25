package br.com.gunbound.emulator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import br.com.gunbound.emulator.handlers.GunBoundBrokerServerHandler;
import br.com.gunbound.emulator.model.entities.ServerOption;
import br.com.gunbound.emulator.utils.PacketDecoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
// Import the EventLoopGroup and I/O handler for NIO
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * Main class that configures and starts the GunBound broker server using Netty.
 * This version uses MultiThreadIoEventLoopGroup per the latest Netty recommendation.
 */
public class GunBoundBrokerServer {

	private String host = "";
	private int port = 0;
	private List<ServerOption> serverOptions = null;
	// List to track active sessions, used to simulate server occupancy.
	private final List<Object> worldSession;

	public GunBoundBrokerServer(String host, int port, List<ServerOption> serverOptions, List<Object> worldSession) {
		this.host = host;
		this.port = port;
		this.serverOptions = serverOptions;
		// Make the list synchronized for thread-safety, since multiple clients can access it.
		this.worldSession = Collections.synchronizedList(worldSession);
	}

	public void start() throws Exception {
		// EventLoopGroup to accept new connections (boss).
		// We use MultiThreadIoEventLoopGroup with the NIO-specific I/O handler.
		EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
		// EventLoopGroup to handle traffic from accepted connections (worker).
		EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(0, NioIoHandler.newFactory()); // 0 = default thread count (2 * cores).

		try {
			// ServerBootstrap is a convenience class for configuring a server.
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class) // NioServerSocketChannel is compatible with the I/O handler.
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						public void initChannel(SocketChannel ch) {
							// Add handlers to the pipeline for each new connection.
                            ch.pipeline().addLast(
                                    // Detect 180 seconds with no data from the client
                                    new IdleStateHandler(180, 0, 0, TimeUnit.SECONDS),
                                    // 1. LoggingHandler for debugging incoming/outgoing traffic.
                                    new LoggingHandler("BrokerLogger", LogLevel.DEBUG),
                                    // 2. Decoder to split packets.
                                    new PacketDecoder(),
                                    // 3. Handler to track outgoing packet size.
                                    new PacketSizeTracker(),
                                    // 4. Handler with business logic.
                                    new GunBoundBrokerServerHandler(serverOptions, worldSession)
                            );
						}
					}).option(ChannelOption.SO_BACKLOG, 128) // Socket options.
					.childOption(ChannelOption.SO_KEEPALIVE, true);

			System.out.println("Broker TCP Bound");
			System.out.println("GunBound Broker - Server Directory:");
			for (ServerOption serverOption : serverOptions) {
				System.out.println("Server: " + serverOption.getServerName() + " - "
						+ serverOption.getServerDescription() + " on port " + serverOption.getServerPort());
			}

			// Bind the port and start accepting connections.
			ChannelFuture f = b.bind(host, port).sync();
			System.out.println("BS: Listening on " + host + ":" + port);

			// Wait until the server socket is closed.
			f.channel().closeFuture().sync();
		} finally {
			// Shut down the EventLoopGroups gracefully.
			workerGroup.shutdownGracefully();
			bossGroup.shutdownGracefully();
		}
	}

	// This main method is only for starting the broker without the game server. Use GunBoundStarter.java for the full server.
	public static void main(String[] args) throws Exception {
		// Default address (0.0.0.0 for all interfaces)
		String host = "0.0.0.0";
		int brokerPort = 8400; // Default Broker Server port

		// Check if the user provided an IP and/or port as argument
		if (args.length > 0) {
			host = args[0]; // First argument is the IP
		}
		if (args.length > 1) {
			try {
				brokerPort = Integer.parseInt(args[1]); // Second argument is the port
			} catch (NumberFormatException e) {
				System.err.println("Invalid port! Using default: " + brokerPort);
			}
		}

		// --- Configure the server list here ---
		List<ServerOption> serverOptions = new ArrayList<>();
		serverOptions.add(new ServerOption("Gunbound Classic", "AVATAR OFF", "127.0.0.1", 8360, 0, 500, true));
		serverOptions.add(new ServerOption("Gunbound Classic", "AVATAR ON", "127.0.0.1", 8361, 0, 100, true));

		// This list simulates player sessions to calculate occupancy. In a real app you would manage it more robustly.
		List<Object> worldSession = new ArrayList<>();

		// Create and start the server.
		new GunBoundBrokerServer(host, brokerPort, serverOptions, worldSession).start();
	}
}