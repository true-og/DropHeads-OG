/*
 * DropHeads - a Bukkit plugin for naturally dropping mob heads
 *
 * Copyright (C) 2017 - 2022 Nathan / EvModder
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.evmodder.DropHeads;

import com.comphenix.protocol.ProtocolLibrary;

import net.evmodder.DropHeads.JunkUtils.NoteblockMode;
import net.evmodder.DropHeads.commands.CommandDropRate;
import net.evmodder.DropHeads.commands.CommandSpawnHead;
import net.evmodder.DropHeads.commands.Commanddebug_all_heads;
import net.evmodder.DropHeads.listeners.BlockClickListener;
import net.evmodder.DropHeads.listeners.CreativeMiddleClickListener;
import net.evmodder.DropHeads.listeners.DeathMessagePacketIntercepter;
import net.evmodder.DropHeads.listeners.EndermanProvokeListener;
import net.evmodder.DropHeads.listeners.EntityDamageListener;
import net.evmodder.DropHeads.listeners.EntityDeathListener;
import net.evmodder.DropHeads.listeners.EntitySpawnListener;
import net.evmodder.DropHeads.listeners.ItemDropListener;
import net.evmodder.DropHeads.listeners.LoreStoreBlockBreakListener;
import net.evmodder.DropHeads.listeners.LoreStoreBlockPlaceListener;
import net.evmodder.DropHeads.listeners.NoteblockPlayListener;
import net.evmodder.DropHeads.listeners.ProjectileFireListener;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import plugin.EvPlugin;
import plugin.FileIO;

//TODO:
// * /dropheads reload
// * rotate painting heads ('face' = painting)
// * creaking head obt
// * improve textures listed at the bottom of head-textures.txt
// * for non-living (Vehicles, Hanging), cancel self-drop if head drop is triggered (configurable)
// * un-dye heads (sheep,shulker) with cauldron (gimmick). Note that washing banners only removes pattern, not color
// * jeb_ sheep head animated phase through colors (gimmick)
// * full /minecraft:give command support (namespaced custom heads in /give)
// * use 'fallback' in TextUtils for head-type, etc.
// * img.shields/io/bukkit/downloads/id ? other badges on GitHub?
// * mob: prefix for /droprate
// * create tester tool to call `getHead(entity)` for every supported variant
// * maybe add another 0 to the threshold for final drop chance vs raw drop chance for deciding to print it in /droprate
//TEST:
// * /droprate edit
// * head-noteblock-sound in ItemMeta
// * Trophies/Luck attribute
// * place-head-block, overwrite-blocks, facing-direction, place-as: KILLER/VICTIM/SERVER
// * middle-click copy with correct item name // WORKS well enough; can still bug out if u middle-click twice
// * update-textures=true (head-textures.txt file overwritten when plugin is updated)
//Export -> JAVADOC -> javadoc sources:
// * EvLib: https://evmodder.github.io/EvLib/
// * HeadDatabaseAPI: https://javadoc.io/doc/com.arcaniax/HeadDatabase-API/1.3.1/
// * Bukkit-1.13: https://hub.spigotmc.org/javadocs/bukkit/
// Search > File... > containing text X > replace Y
// ` TellrawUtils.` -> ` `, `(TellrawUtils.` -> `(`, `>TellrawUtils.` -> `>`, `(HeadUtils.` -> `(`
public final class DropHeads extends EvPlugin{
	private static DropHeads instance;
	public static DropHeads getPlugin(){return instance;}
	private InternalAPI api; public HeadAPI getAPI(){return api;} public InternalAPI getInternalAPI(){return api;}
	private DropChanceAPI dropChanceAPI; public DropChanceAPI getDropChanceAPI(){return dropChanceAPI;}
	private DeathMessagePacketIntercepter deathMessageBlocker;
	private boolean LOGFILE_ENABLED;
	private String LOGFILE_NAME;

	@Override public void onEvEnable(){
		instance = this;
		ProtocolLibrary.getProtocolManager();
		final NoteblockMode m = config.isBoolean("noteblock-mob-sounds")
				? (config.getBoolean("noteblock-mob-sounds") ? NoteblockMode.LISTENER : NoteblockMode.OFF)
						: JunkUtils.parseEnumOrDefault(config.getString("noteblock-mob-sounds", "OFF"), NoteblockMode.OFF);
		final boolean CRACKED_IRON_GOLEMS_ENABLED = config.getBoolean("cracked-iron-golem-heads", false);;
		api = new InternalAPI(m, CRACKED_IRON_GOLEMS_ENABLED);
		final boolean GLOBAL_PLAYER_BEHEAD_MSG = config.getString("behead-announcement.player",
				config.getString("behead-announcement.default", "GLOBAL")).toUpperCase().equals("GLOBAL");
		final boolean WANT_TO_REPLACE_PLAYER_DEATH_MSG = config.getBoolean("behead-announcement-replaces-player-death-message",
				config.getBoolean("behead-announcement-replaces-death-message", true));
		if(WANT_TO_REPLACE_PLAYER_DEATH_MSG && !GLOBAL_PLAYER_BEHEAD_MSG){
			getLogger().warning("behead-announcement-replaces-player-death-message is true, but behead-announcement.player is not GLOBAL");
		}
		final boolean REPLACE_PLAYER_DEATH_MSG = WANT_TO_REPLACE_PLAYER_DEATH_MSG && GLOBAL_PLAYER_BEHEAD_MSG;
		final boolean REPLACE_PET_DEATH_MSG = config.getBoolean("behead-message-replaces-pet-death-message", true);
		if(REPLACE_PLAYER_DEATH_MSG || REPLACE_PET_DEATH_MSG){
			deathMessageBlocker = new DeathMessagePacketIntercepter(REPLACE_PLAYER_DEATH_MSG, REPLACE_PET_DEATH_MSG);
		}
		dropChanceAPI = new DropChanceAPI(REPLACE_PLAYER_DEATH_MSG, REPLACE_PET_DEATH_MSG, deathMessageBlocker);
		new EntityDeathListener(deathMessageBlocker);

		if(config.getBoolean("track-mob-spawns", true)){
			getServer().getPluginManager().registerEvents(new EntitySpawnListener(), this);
		}
		if(config.getBoolean("drop-for-ranged-kills", false) && config.getBoolean("use-ranged-weapon-for-looting", true)){
			getServer().getPluginManager().registerEvents(new ProjectileFireListener(), this);
		}
		if(config.getBoolean("drop-for-indirect-kills", false) && !config.getBoolean("drop-for-nonplayer-kills", false)){
			getServer().getPluginManager().registerEvents(new EntityDamageListener(), this);
		}
		if(config.getBoolean("refresh-textures", false)){
			getServer().getPluginManager().registerEvents(new ItemDropListener(), this);
		}
		if(config.getBoolean("head-click-listener", true) || CRACKED_IRON_GOLEMS_ENABLED){
			getServer().getPluginManager().registerEvents(new BlockClickListener(api.translationsFile), this);
		}
		if(config.getBoolean("save-custom-lore", true)){
			getServer().getPluginManager().registerEvents(new LoreStoreBlockPlaceListener(), this);
			getServer().getPluginManager().registerEvents(new LoreStoreBlockBreakListener(), this);
		}
		if(config.getBoolean("fix-creative-nbt-copy", true)){
			getServer().getPluginManager().registerEvents(new CreativeMiddleClickListener(), this);
		}
		//TODO: Wait for Minecraft to support custom-namespaced items in /give
		//		if(config.getBoolean("substitute-dropheads-in-give-command", false)){
		//			getServer().getPluginManager().registerEvents(new GiveCommandPreprocessListener(), this);
		//		}
		if(!config.getStringList("endermen-camouflage-heads").isEmpty()){
			getServer().getPluginManager().registerEvents(new EndermanProvokeListener(), this);
		}
		if(m == NoteblockMode.LISTENER){
			getServer().getPluginManager().registerEvents(new NoteblockPlayListener(), this);
		}

		new CommandSpawnHead(this);
		new CommandDropRate(this);
		new Commanddebug_all_heads(this);

		LOGFILE_ENABLED = config.getBoolean("log.enable", false);
		if(LOGFILE_ENABLED) LOGFILE_NAME = config.getString("log.filename", "log.txt");
	}

	@Override public void onEvDisable(){if(deathMessageBlocker != null) deathMessageBlocker.unregisterAll();}

	public boolean writeToLogFile(String line){
		if(!LOGFILE_ENABLED) return false;
		// Write to log
		line = line.replace("\n", "")+"\n";
		getLogger().fine("Writing line to logfile: "+line);
		return FileIO.saveFile(LOGFILE_NAME, line, /*append=*/true);
	}

	/**
	 * Removes all formatting from a TextComponent using MiniMessage.
	 *
	 * @param component The Adventure Component to process.
	 * @return A plain string without formatting.
	 */
	public static String stripColor(TextComponent component) {
		if (component == null) return null;
		// Serialize the component to plain text without formatting
		return LegacyComponentSerializer.legacySection().serialize(component);
	}

	// Helper method to determine "a" or "an" for the given word
	public static String getIndefiniteArticle(String word) {
		if (word == null || word.isEmpty()) {
			return "a";
		}
		char firstChar = Character.toLowerCase(word.charAt(0));
		if ("aeiou".indexOf(firstChar) != -1) {
			return "an";
		}
		return "a";
	}
}