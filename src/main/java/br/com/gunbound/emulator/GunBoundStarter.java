package br.com.gunbound.emulator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import br.com.gunbound.emulator.buddy.BuddyUdpServer;
import br.com.gunbound.emulator.model.entities.ServerOption;

public class GunBoundStarter {
	private static final int BROKER_PORT = 8400;
	private static final int GAME_SERVER_PORT = 8360;
	private static final int BUDDY_SERVER_PORT = 8352;

	// Bind on all network interfaces.
	private static final String BIND_HOST = "0.0.0.0";
	// Address advertised to clients in the server list.
	private static final String ADVERTISED_HOST = "51.191.171.234";

	public static void main(String[] args) {
		ExecutorService executor = Executors.newFixedThreadPool(3);

		List<Object> gameServerSessions = Collections.synchronizedList(new ArrayList<>());

		List<ServerOption> serverOptions = new ArrayList<>();
		try {
			serverOptions.add(new ServerOption("GunBound Classic", "Avatar OFF", ADVERTISED_HOST, GAME_SERVER_PORT, 0,
					500, true));
		} catch (Exception e) {
			System.err.println("Error creating ServerOption for Game Server: " + e.getMessage());
			return;
		}

		GunBoundGameServer gameServer = new GunBoundGameServer(GAME_SERVER_PORT);
		AtomicBoolean gameServerStarted = new AtomicBoolean(false);

		executor.submit(() -> {
			try {
				gameServer.start();
				gameServerStarted.set(true);
			} catch (Exception e) {
				System.err.println("Failed to start Game Server on port " + GAME_SERVER_PORT + "!");
				e.printStackTrace();
			}
		});

		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			System.err.println("Game Server startup interrupted.");
			Thread.currentThread().interrupt();
		}

		if (!gameServerStarted.get()) {
			System.err.println("Could not start Game Server. Aborting Broker Server startup.");
			executor.shutdownNow();
			return;
		}

		GunBoundBrokerServer gunBoundBrokerServer = new GunBoundBrokerServer(BIND_HOST, BROKER_PORT, serverOptions,
				gameServerSessions);
		executor.submit(() -> {
			try {
				gunBoundBrokerServer.start();
			} catch (Exception e) {
				System.err.println("Failed to start Broker Server on port " + BROKER_PORT + "!");
				e.printStackTrace();
			}
		});

		GunBoundBuddyServer buddyServer = new GunBoundBuddyServer(BUDDY_SERVER_PORT);
		executor.submit(() -> {
			try {
				buddyServer.start();
			} catch (Exception e) {
				System.err.println("Failed to start Buddy Server on port " + BUDDY_SERVER_PORT + "!");
				e.printStackTrace();
			}
		});

		executor.submit(new BuddyUdpServer(BUDDY_SERVER_PORT));

		System.out.println("--- GunBound Starter Started ---");
		System.out.println("Broker Server listening on " + BIND_HOST + ":" + BROKER_PORT);
		System.out.println("Game Server listening on " + BIND_HOST + ":" + GAME_SERVER_PORT);
		System.out.println("Buddy Server listening on " + BIND_HOST + ":" + BUDDY_SERVER_PORT + " (TCP+UDP)");
		System.out.println("Clients connect to " + ADVERTISED_HOST + " (broker " + BROKER_PORT + ", game "
				+ GAME_SERVER_PORT + ", buddy " + BUDDY_SERVER_PORT + ")");
		System.out.println("Press Ctrl+C to stop.");

		try {
			executor.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS);
		} catch (InterruptedException e) {
			System.out.println("Servers stopped by interrupt.");
			Thread.currentThread().interrupt();
		} finally {
			executor.shutdownNow();
			System.out.println("--- GunBound Starter Stopped ---");
		}
	}
}
