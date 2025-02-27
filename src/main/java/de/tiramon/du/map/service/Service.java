package de.tiramon.du.map.service;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.javafx.collections.ObservableListWrapper;

import de.tiramon.du.map.DuMapDialog;
import de.tiramon.du.map.InstanceProvider;
import de.tiramon.du.map.model.DuLogRecord;
import de.tiramon.du.map.model.Ore;
import de.tiramon.du.map.model.Scan;
import de.tiramon.du.map.model.Scanner;
import de.tiramon.du.map.model.Scanner.ScannerState;
import de.tiramon.du.map.model.Sound;
import de.tiramon.du.map.model.User;
import javafx.beans.Observable;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

public class Service {
	protected Logger log = LoggerFactory.getLogger(getClass());

	private String soundSetting = InstanceProvider.getProperties().getProperty("territoryscan.sound");

	private DuMapService dumapService = new DuMapService();

	private Pattern asset = Pattern.compile("onTerritoryClaimed\\(planet=(?<planetId>\\d+), tile=(?<tileId>\\d+)\\)");
	private Pattern asset2 = Pattern.compile("Received rights for asset AssetId:\\[type = Territory, construct = ConstructId:\\[constructId = 0\\], element = ElementId:\\[elementId = 0\\], territory = TerritoryTileIndex:\\[planetId = (?<planetId>\\d+), tileIndex = (?<tileId>\\d+)\\], item = ItemId:\\[typeId = 0, instanceId = 0, ownerId = EntityId:\\[playerId = 0, organizationId = 0\\]\\], organization = OrganizationId:\\[organizationId = 0\\]\\]");
	private Pattern userPattern = Pattern.compile("LoginResponse:\\[playerId = (?<playerId>\\d+), username = (?<playerName>.+?), communityId = (?<communityId>\\d+), ip = [\\d+\\.\\|]+, timeSeconds = @\\((?<loginTimestamp>\\d+)\\) \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\]");

	private Pattern scanner_start = Pattern.compile("AssetEvent: Played sound event Construct_Element_TerritoryScanner_Start -- id (?<scannerid>\\d+)");
	private Pattern scanner_stop = Pattern.compile("AssetEvent: Played sound event Construct_Element_TerritoryScanner_Stop -- id (?<scannerid>\\d+)");
	private Pattern scanner_result_position = Pattern.compile("TerritoryScan\\[\\d+#(?<scannerid>\\d+)\\] end: lasted (\\d+\\.\\d+) seconds coordinates: (?<position>::pos\\{\\d+,\\d+,-?\\d+\\.\\d+,-?\\d+\\.\\d+,-?\\d+\\.\\d+})");

	private Pattern scanOrePattern = Pattern.compile("TerritoryScan\\[(?<scannerid>\\d+)\\]: material: (?<oreName>[A-Za-z ]+) : (?<oreAmount>[\\de+\\.]+) \\* \\d+");
	private User user;
	private Set<Long> knownAssets = ConcurrentHashMap.newKeySet();

	private ObjectProperty<Sound> currentSoundProperty = new SimpleObjectProperty<>();
	private List<Sound> soundList = new ArrayList<>();

	private Map<Long, Scan> currentScans = new HashMap<>();
	private ConcurrentHashMap<Long, Scanner> scannerMap = new ConcurrentHashMap<>();
	private ObservableList<Scanner> list = FXCollections.observableList(new CopyOnWriteArrayList<>(), item -> new Observable[] { item.timeLeftProperty(), item.positionProperty(), item.stateProperty() });

	public Service() {
		initSound();
	}

	public void initSound() {
		soundList.add(new Sound("None", null));
		soundList.add(new Sound("Pling", "35631__reinsamba__crystal-glass.mp3"));
		soundList.add(new Sound("Alien Voice", "territoryscancompleted_alienvoice.mp3"));
		log.info("Territory Scanner analysis activated");
		currentSoundProperty.set(soundList.get(1));

		if (soundSetting != null && !soundSetting.isBlank()) {
			Sound sound = soundList.stream().filter(s -> s.getTitle().equalsIgnoreCase(soundSetting)).findFirst().orElse(null);
			if (sound != null) {
				currentSoundProperty.set(sound);
			}
		}
	}

	public void handleAsset(DuLogRecord record) {
		Matcher matcher = asset.matcher(record.message);
		if (matcher.matches()) {
			long planetId = Long.valueOf(matcher.group("planetId"));
			long tileId = Long.valueOf(matcher.group("tileId"));
			log.info("received asset message for {} on {}", tileId, planetId);
			if (knownAssets.add(planetId * 1000000 + tileId)) {
				dumapService.sendClaimedTile(planetId, tileId, record.millis);
			}
		} else {
			int bp = 0;
		}
	}

	public void handleScanStatusChange(DuLogRecord record) {
		{
			Matcher matcher = scanner_start.matcher(record.message);
			if (matcher.matches()) {
				long scannerid = Long.valueOf(matcher.group("scannerid"));
				Scanner scanner = new Scanner(scannerid);
				if (scannerMap.putIfAbsent(scannerid, scanner) == null) {
					log.info("new scanner {}", scannerid);
					list.add(scanner);
				}
				scannerMap.get(scannerid).setState(ScannerState.STARTED, record.millis);
				scannerMap.get(scannerid).setSubmited(false);
				log.info("Scanner " + scannerid + " started");
				return;
			} else {
				int bp = 0;
			}
		}
		{
			Matcher matcher = scanner_stop.matcher(record.message);
			if (matcher.matches()) {
				long scannerid = Long.valueOf(matcher.group("scannerid"));
				scannerMap.get(scannerid).setState(ScannerState.FINISHED, record.millis);
				log.info("Scanner " + scannerid + " finished");
				scannerMap.get(scannerid).setSubmited(false);
				playSound();
				return;
			} else {
				int bp = 0;
			}
		}
	}

	public void handleScanPosition(DuLogRecord record) {
		Matcher matcher = scanner_result_position.matcher(record.message);
		if (matcher.matches()) {
			long scannerid = Long.valueOf(matcher.group("scannerid"));
			String position = matcher.group("position").replace("-0.0000", "0.0000");
			Scanner scanner = scannerMap.get(scannerid);
			scanner.setPosition(position);
			Scan scan = new Scan(position, record.millis);

			log.info("Scanner " + scannerid + " at postion " + position);
			currentScans.put(scannerid, scan);
		} else {
			int bp = 0;
		}
	}

	public void handleScanOre(DuLogRecord record) {
		Matcher matcher = scanOrePattern.matcher(record.message);
		if (matcher.find()) {
			final long scannerId = Long.parseLong(matcher.group("scannerid"));

			Ore ore = Ore.byName(matcher.group("oreName"));
			long oreAmount = Double.valueOf(matcher.group("oreAmount")).longValue();
			Scan scan = currentScans.get(scannerId);
			if (scan != null) {
				if (scan.getOres().containsKey(ore)) {
					log.info("received redundant ore scan result, so scan is done");
					currentScans.remove(scannerId);
					dumapService.sendScan(scan.getPlanet(), scan.getLat(), scan.getLon(), scan.getTimestamp(), scan.getOres());
					scannerMap.get(scannerId).setSubmited(true);
				} else {
					scan.add(ore, oreAmount);
					log.info("Scanner {} found {} {}", scannerId, oreAmount, ore.getName());
				}
			} else {
				log.trace("received redundant ore scan result");
			}
		} else {
			int bp = 0;
		}
	}

	public void handleUser(DuLogRecord record) {
		Matcher matcher = userPattern.matcher(record.message);
		if (matcher.find()) {
			Long playerId = Long.parseLong(matcher.group("playerId"));
			Long communityId = Long.parseLong(matcher.group("communityId"));
			String playerName = matcher.group("playerName");
			user = new User(playerId, communityId, playerName);
			log.info("Player is id: {} communityId: {} name: {}", playerId, communityId, playerName);
		} else {
			throw new RuntimeException("Invalid Pattern");
		}
	}

	public void playSound() {
		if (!DuMapDialog.init) {
			return;
		}
		String strFilename = currentSoundProperty.get().getResourceFileName();
		if (strFilename != null) {
			URL url = getClass().getClassLoader().getResource(strFilename);
			Media hit = new Media(url.toString());
			MediaPlayer mediaPlayer = new MediaPlayer(hit);

			mediaPlayer.play();
		}
		log.debug("played sound");
	}

	public ObservableList<Sound> getSoundOptions() {
		return new ObservableListWrapper<>(soundList);
	}

	public ObjectProperty<Sound> currentSoundProperty() {
		return currentSoundProperty;
	}

	public ObservableList<Scanner> getScannerList() {
		return list;
	}

	public void handleAssetRights(DuLogRecord record) {
		Matcher matcher = asset2.matcher(record.message);
		if (matcher.matches()) {
			long planetId = Long.valueOf(matcher.group("planetId"));
			long tileId = Long.valueOf(matcher.group("tileId"));
			log.info("received asset rights message for {} on {}", tileId, planetId);
			if (knownAssets.add(planetId * 1000000 + tileId)) {
				dumapService.sendClaimedTile(planetId, tileId, record.millis);
			}
		} else {
			int bp = 0;
		}
	}
}
