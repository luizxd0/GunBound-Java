package br.com.gunbound.emulator.handlers;

import java.util.List;

import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import io.netty.util.AttributeKey;

public class GameAttributes {
	//SOMA DOS PACOTES DE CADA SESSAO (SEM ISSO NAO SOMOS NADA)
	public static final AttributeKey<Integer> PACKET_TX_SUM = AttributeKey.valueOf("packetTxSum");
	
	//Atributos do Usuario
	//USUARIO DA SESSAO
	public static final AttributeKey<PlayerSession> USER_SESSION = AttributeKey.valueOf("userSession");
	//AUTHTOKEN DWORD (NO CLIENTE) ESSENCIAL PARA CRIPTOGRAFIA
	public static final AttributeKey<byte[]> AUTH_TOKEN = AttributeKey.valueOf("authToken");
	//gera um valor unico pra sessao
	public static final AttributeKey<byte[]> SESSION_UNIQUE = AttributeKey.valueOf("sessionUnique");
	//guarda a versao do Cliente que o player ta usando
	public static final AttributeKey<Integer> CLIENT_VERSION = AttributeKey.valueOf("clientVersion");
	// cache da ultima busca SVC_USER_ID para bridge de add buddy
	public static final AttributeKey<String> LAST_USER_SEARCH_USER_ID = AttributeKey.valueOf("lastUserSearchUserId");
	public static final AttributeKey<String> LAST_USER_SEARCH_NICK = AttributeKey.valueOf("lastUserSearchNick");
	public static final AttributeKey<Long> LAST_USER_SEARCH_TS = AttributeKey.valueOf("lastUserSearchTs");
	// aplica regra de filtro padrÃ£o da lista de salas na primeira 0x2100 apÃ³s entrar no canal
	public static final AttributeKey<Boolean> INITIAL_ROOM_LIST_PENDING = AttributeKey.valueOf("initialRoomListPending");
	public static final AttributeKey<Boolean> WORLD_LIST_JOIN_PENDING = AttributeKey.valueOf("worldListJoinPending");
	// filtro atual selecionado pelo cliente em SVC_ROOM_SORTED_LIST (1=ALL, 2=WAITING)
	public static final AttributeKey<Integer> CURRENT_ROOM_LIST_FILTER = AttributeKey.valueOf("currentRoomListFilter");
	// pagina atual da lista de salas exibida pelo cliente
	public static final AttributeKey<Integer> CURRENT_ROOM_LIST_START_INDEX = AttributeKey.valueOf("currentRoomListStartIndex");
	// ids das salas atualmente exibidas ao cliente (usado para refresh de status sem reordenar)
	public static final AttributeKey<List<Integer>> CURRENT_ROOM_LIST_ROOM_IDS = AttributeKey.valueOf("currentRoomListRoomIds");
	
	
	
	
	
	//Game atributes
	
	public static AttributeKey<Integer> DRAGON_N_KINGHT_RATIO = AttributeKey.valueOf("DRAGON_N_KINGHT_RATIO");
	
	
	//Gerenciador dos lobbys (canais no gunbound)
	//public static final AttributeKey<GunBoundLobbyManager> LOBBY_MANAGER = AttributeKey.valueOf("LobbyManager");
}
