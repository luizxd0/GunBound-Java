package br.com.gunbound.emulator.lobby;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.packets.writers.PacketWriter;
import br.com.gunbound.emulator.utils.PacketUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class GunBoundLobbyManager {
	// 1. A Ãºnica instÃ¢ncia da classe (Singleton)
	private static volatile GunBoundLobbyManager instance;

	private static final int NUM_CHANNELS = 8;
	private final List<GunBoundLobby> channels;

	// 2. Construtor privado para evitar instÃ¢ncias diretas de fora
	private GunBoundLobbyManager() {
		channels = new CopyOnWriteArrayList<>();
		for (int i = 0; i < NUM_CHANNELS; i++) {
			// Inicializa os canais de lobby. Use 'i + 1' para IDs de 1 a NUM_CHANNELS.
			channels.add(new GunBoundLobby(i));
		}
		System.out.println("GS: " + NUM_CHANNELS + " canais de lobby inicializados.");
	}

	// 3. MÃ©todo estÃ¡tico pÃºblico para obter a Ãºnica instÃ¢ncia da classe
	// (thread-safe)
	public static GunBoundLobbyManager getInstance() {
		if (instance == null) { // Primeira checagem (sem lock)
			synchronized (GunBoundLobbyManager.class) { // Sincroniza apenas se a instÃ¢ncia for nula
				if (instance == null) { // Segunda checagem (dentro do lock)
					instance = new GunBoundLobbyManager();
				}
			}
		}
		return instance;
	}

	// --- MÃ©todos de Gerenciamento do Lobby ---

	public List<GunBoundLobby> getAllLobby() {
		return channels;
	}

	public Optional<GunBoundLobby> getLobbyById(int id) {
		// Valida o ID do canal para evitar IndexOutOfBoundsException e retornar
		// Optional.empty()
		if (id < 0 || id > NUM_CHANNELS) {
			System.err.println("GS: Tentativa de acessar canal invÃ¡lido: " + (id + 1) + " [" + id + "]");
			return Optional.empty();
		}
		// Os canais sÃ£o armazenados em uma lista de 0 a N-1, entÃ£o 'id - 1'
		return Optional.of(channels.get(id));
	}

	/**
	 * Encontra o melhor canal para um novo jogador, priorizando os 3 primeiros.
	 * 
	 * @return O ID do canal recomendado (1-8), ou -1 se todos os canais estiverem
	 *         cheios.
	 */
	public int findBestChannelForNewPlayer() {
		// --- NÃ­vel 1: Canais de Alta Prioridade (1, 2, 3) ---
		for (int i = 0; i <= 3; i++) {
			final int channelId = i;
			boolean channelAvailable = getLobbyById(channelId).map(channel -> !channel.isFull()).orElse(false);

			if (channelAvailable) {
				System.out.println(
						"GS: [DistribuiÃ§Ã£o] Canal prioritÃ¡rio " + channelId + " encontrado para novo jogador.");
				return channelId;
			}
		}

		// --- NÃ­vel 2: Canais de Overflow (4 a 8) ---
		System.out.println("GS: [DistribuiÃ§Ã£o] Canais prioritÃ¡rios cheios. Procurando em canais de overflow...");

		return channels.stream().filter(channel -> channel.getId() > 3 && !channel.isFull())
				.min(Comparator.comparingInt(GunBoundLobby::getPlayerCount)).map(GunBoundLobby::getId)
				.orElse(-1);
	}

	public void playerJoinLobby(PlayerSession player, int channelId) {
		if (player == null) {
			System.err.println("GS: Tentativa de mover um PlayerSession nulo para Lobby " + (channelId + 1));
			return;
		}

		getLobbyById(channelId).ifPresentOrElse(lobby -> {
			// Remove o jogador do lobby atual, se ele jÃ¡ estiver em um
			if (player.getCurrentLobby() != null) {
				player.getCurrentLobby().removePlayerFromLobby(player);
				System.out.println("GS: Jogador " + player.getNickName() + " saiu do lobby anterior.");
			}

			// Adiciona o jogador ao novo lobby
			lobby.addPlayerToLobby(player);
			
			player.setCurrentLobby(lobby); // Atualiza a referÃªncia de lobby no PlayerSession
			System.out.println("GS: Jogador " + player.getNickName() + " entrou no lobby " + (channelId + 1));

			// Notificar outros jogadores no lobby sobre a entrada deste jogador
		}, () -> {
			System.err.println("GS: Tentativa de jogador " + player.getNickName() + " entrar em lobby invÃ¡lido: "
					+ (channelId + 1));
		});
		broadcastPlayerJoined(player); // NotificaÃ§Ã£o automÃ¡tica
	}

	public void playerLeaveLobby(PlayerSession player) {
		if (player == null || player.getCurrentLobby() == null) {
			// O jogador nÃ£o estÃ¡ em nenhum lobby ou o objeto PlayerSession Ã© nulo.
			System.out.println("GS: Tentativa de remover jogador nulo ou sem lobby.");
			return;
		}

		player.getCurrentLobby().releaseSlot(player.getChannelPosition()); // Devolve o slot para a fila
		broadcastPlayerLeft(player); // inserido agora pra tirar da classe
		player.getCurrentLobby().removePlayerFromLobby(player);
		player.setCurrentLobby(null); // Limpa a referÃªncia de lobby no PlayerSession
		player.setChannelPosition(-1); // adicionado depois (VERIFICAR) ********************************************
		System.out.println("GS: Jogador " + player.getNickName() + " saiu do lobby.");

		// Notificar outros jogadores no lobby sobre a saÃ­da deste jogador
	}

	public Collection<PlayerSession> getPlayersInLobby(int channelId) {
		return getLobbyById(channelId).map(GunBoundLobby::getPlayersInLobby) // Mapeia o Optional para o Map de
																					// jogadores
				.map(Map::values) // Mapeia o Map para a ColeÃ§Ã£o de jogadores
				.orElse(Collections.emptyList()); // Retorna uma lista vazia se o canal nÃ£o existir
	}

	/**
	 * Notifica todos os jogadores no lobby (exceto o que acabou de entrar) que um
	 * novo jogador chegou.
	 * 
	 * @param newPlayer O jogador que acabou de entrar.
	 */
	private void broadcastPlayerJoined(PlayerSession newPlayer) {
		GunBoundLobby currenttLobby = newPlayer.getCurrentLobby();
		// logs para depuracao
		System.out.println("[DEBUG] Iniciando broadcastPlayerJoined para: " + newPlayer.getNickName() + " no Canal ID: "
				+ currenttLobby.getId());
		System.out.println("[DEBUG] Tamanho atual do lobby: " + currenttLobby.getPlayersInLobby().size());
		System.out.println("[DEBUG] Jogadores no lobby: " + currenttLobby.getPlayersInLobby().keySet().toString());
		// Usa o opcode 0x200E para notificar sobre a entrada
		final int OPCODE_PLAYER_JOINED = 0x200E;
		// snapshot para evitar concorrencia
		Collection<PlayerSession> recipients = new ArrayList<>(currenttLobby.getPlayersInLobby().values());
		for (PlayerSession existingPlayer : recipients) {
			if (!existingPlayer.equals(newPlayer)) {
				existingPlayer.getPlayerCtxChannel().eventLoop().execute(() -> {
					ByteBuf notificationPayload = PacketWriter.writeJoinNotification(newPlayer);
					try {
						ByteBuf notificationPacket = PacketUtils.generatePacket(existingPlayer, OPCODE_PLAYER_JOINED,
								notificationPayload, false);
						existingPlayer.getPlayerCtxChannel().writeAndFlush(notificationPacket);
					} finally {
						notificationPayload.release();
					}
				});
			}
		}
	}

	/**
	 * Notifica todos os jogadores no lobby (exceto o que estÃ¡ a sair) que um
	 * jogador saiu.
	 * 
	 * @param leavingPlayer O jogador que estÃ¡ a sair.
	 */
	private void broadcastPlayerLeft(PlayerSession leavingPlayer) {
		GunBoundLobby currenttLobby = leavingPlayer.getCurrentLobby();
		// logs para depuracao
		System.out.println("[DEBUG] Iniciando Lobby.broadcastPlayerLeft para: " + leavingPlayer.getNickName()
				+ " no Canal ID: " + currenttLobby.getId());
		System.out.println("[DEBUG] Tamanho atual do lobby: " + currenttLobby.getPlayersInLobby().size());
		System.out.println("[DEBUG] Jogadores no lobby: " + currenttLobby.getPlayersInLobby().keySet().toString());
		System.out.println("[DEBUG] Posicao do jogador que esta a sair (" + leavingPlayer.getNickName() + "): "
				+ leavingPlayer.getChannelPosition());
		// Se o lobby so tiver 1 pessoa (o que esta a sair), nao ha ninguem para notificar.
		if (currenttLobby.getPlayersInLobby().size() <= 1) {
			System.out.println(leavingPlayer.getNickName() + " Saiu e nao havia ninguem para notificar.");
			return;
		}
		final int OPCODE_PLAYER_LEFT = 0x200F;
		// snapshot para evitar concorrencia
		Collection<PlayerSession> recipients = new ArrayList<>(currenttLobby.getPlayersInLobby().values());
		for (PlayerSession remainingPlayer : recipients) {
			if (remainingPlayer.equals(leavingPlayer)) {
				System.out.println(
						"[DEBUG] Pulando notificacao para o proprio jogador: " + remainingPlayer.getNickName());
				continue;
			}
			System.out.println(leavingPlayer.getNickName() + " Saiu e " + remainingPlayer.getNickName() + " ["
					+ remainingPlayer.getChannelPosition() + "] Foi notificado");
			remainingPlayer.getPlayerCtxChannel().eventLoop().execute(() -> {
				ByteBuf notificationPayload = Unpooled.buffer(2).writeShortLE(leavingPlayer.getChannelPosition());
				try {
					ByteBuf notificationPacket = PacketUtils.generatePacket(remainingPlayer, OPCODE_PLAYER_LEFT,
							notificationPayload, false);
					remainingPlayer.getPlayerCtxChannel().writeAndFlush(notificationPacket);
				} finally {
					notificationPayload.release();
				}
			});
		}
		System.out.println(leavingPlayer.getNickName() + " Saiu e gerou notificacao para todos os jogadores restantes.");
	}

}

