package br.com.gunbound.emulator.playdata;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class MapDataLoader {

	private static final Map<Integer, MapData> MAPS_BY_ID;

	static {
		System.out.println("Loading map data from map_data.json...");
		List<MapData> loadedMaps = Collections.emptyList();
		Gson gson = new Gson();

		// Expected type: a List of MapData
		Type mapListType = new TypeToken<List<MapData>>() {
		}.getType();

		// Try to read the file from the 'resources' folder
		try (InputStream is = MapDataLoader.class.getClassLoader().getResourceAsStream("map_data.json")) {
			if (is == null) {
				System.err.println("CRITICAL ERROR: Could not find map_data.json in resources!");
			} else {
				InputStreamReader reader = new InputStreamReader(is);
				loadedMaps = gson.fromJson(reader, mapListType);
			}
		} catch (Exception e) {
			System.err.println("CRITICAL ERROR: Failed to load or parse map_data.json!");
			e.printStackTrace();
		}

		// Convert the list to a map for fast lookups by ID
		MAPS_BY_ID = loadedMaps.stream().collect(Collectors.toMap(MapData::getMapId, Function.identity()));

		System.out.println(MAPS_BY_ID.size() + " maps loaded successfully.");
	}

	/**
	 * Gets map data by its ID.
	 *
	 * @param mapId The map ID.
	 * @return The corresponding MapData object, or null if not found.
	 */
	public static MapData getMapById(int mapId) {
		return MAPS_BY_ID.get(mapId);
	}

	/**
	 * Returns a list of all loaded maps.
	 *
	 * @return A list of all MapData.
	 */
	public static List<MapData> getAllMaps() {
		return List.copyOf(MAPS_BY_ID.values());
	}
}