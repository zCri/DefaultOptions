package net.blay09.mods.defaultoptions;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLConstructionEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mod(modid = DefaultOptions.MOD_ID, name = "Default Options", clientSideOnly = true, acceptedMinecraftVersions = "[1.12]", dependencies = "after:journeymap")
@Mod.EventBusSubscriber
public class DefaultOptions {

    private static class DefaultBinding {
        public final int keyCode;
        public final KeyModifier modifier;

        public DefaultBinding(int keyCode, KeyModifier modifier) {
            this.keyCode = keyCode;
            this.modifier = modifier;
        }
    }

    public static final String MOD_ID = "defaultoptions";
    public static final Logger logger = LogManager.getLogger(MOD_ID);

    @Mod.Instance
    public static DefaultOptions instance;

    private static Map<String, DefaultBinding> defaultKeys = Maps.newHashMap();
    private static List<String> knownKeys = Lists.newArrayList();

    @Mod.EventHandler
    public void construct(FMLConstructionEvent event) {
        preStartGame();

        Minecraft mc = Minecraft.getMinecraft();
        GameSettings gameSettings = mc.gameSettings;
        gameSettings.loadOptions();

        // We need to update the language here manually because it's set prior to construct
        mc.getLanguageManager().currentLanguage = gameSettings.language;

        // We need to update the resource pack repository manually because it's set prior to construct
        ResourcePackRepository resourcePackRepository = mc.getResourcePackRepository();
        resourcePackRepository.updateRepositoryEntriesAll();
        List<ResourcePackRepository.Entry> repositoryEntriesAll = resourcePackRepository.getRepositoryEntriesAll();
        List<ResourcePackRepository.Entry> repositoryEntries = Lists.newArrayList();
        Iterator<String> it = gameSettings.resourcePacks.iterator();
        while (it.hasNext()) {
            String packName = it.next();
            for (ResourcePackRepository.Entry entry : repositoryEntriesAll) {
                if (entry.getResourcePackName().equals(packName)) {
                    if (entry.getPackFormat() == 3 || gameSettings.incompatibleResourcePacks.contains(entry.getResourcePackName())) {
                        repositoryEntries.add(entry);
                        break;
                    }

                    it.remove();
                    logger.warn("[Vanilla Behaviour] Removed selected resource pack {} because it's no longer compatible", entry.getResourcePackName());
                }
            }
        }

        resourcePackRepository.setRepositories(repositoryEntries);
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ClientCommandHandler.instance.registerCommand(new CommandDefaultOptions());
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new DefaultDifficultyHandler());
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        File defaultOptions = new File(getDefaultOptionsFolder(), "options.txt");
        if (!defaultOptions.exists()) {
            saveDefaultOptions();
        }

        File defaultOptionsOF = new File(getDefaultOptionsFolder(), "optionsof.txt");
        if (!defaultOptionsOF.exists()) {
            saveDefaultOptionsOptiFine();
        }
        
        File defaultOptionsShaders = new File(getDefaultOptionsFolder(), "optionsshaders.txt");
        if (!defaultOptionsShaders.exists()) {
            saveDefaultOptionsShaders();
        }

        File defaultKeybindings = new File(getDefaultOptionsFolder(), "keybindings.txt");
        if (!defaultKeybindings.exists()) {
            saveDefaultMappings();
        }

        reloadDefaultMappings();
    }

    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (event.getModID().equals(MOD_ID)) {
            ConfigManager.sync(MOD_ID, Config.Type.INSTANCE);
            DefaultOptionsConfig.onConfigReload();
        }
    }

    public static void preStartGame() {
        File mcDataDir = Minecraft.getMinecraft().mcDataDir;
        File optionsFile = new File(mcDataDir, "options.txt");
        boolean firstRun = !optionsFile.exists();
        if (firstRun) {
            applyDefaultOptions();
        }
        File optionsFileOF = new File(mcDataDir, "optionsof.txt");
        if (!optionsFileOF.exists()) {
            applyDefaultOptionsOptiFine();
        }
        File optionsFileShaders = new File(mcDataDir, "optionsshaders.txt");
        if (!optionsFileShaders.exists()) {
            applyDefaultOptionsShaders();
        }
        File serversDatFile = new File(mcDataDir, "servers.dat");
        if (!serversDatFile.exists()) {
            applyDefaultServers();
        }
        File overwriteConfig = new File(mcDataDir, "overwrite-config");
        if (firstRun || overwriteConfig.exists()) {
            applyDefaultConfig();
            if (overwriteConfig.exists() && !overwriteConfig.delete()) {
                logger.warn("Could not delete overwrite-config file. Configs will be overwritten from defaults upon next run unless you delete the file manually.");
            }
        }
    }

    private static boolean applyDefaultServers() {
        try {
            FileUtils.copyFile(new File(getDefaultOptionsFolder(), "servers.dat"), new File(Minecraft.getMinecraft().mcDataDir, "servers.dat"));
            return true;
        } catch (IOException e) {
            logger.error(e);
            return false;
        }
    }

    public static boolean applyDefaultConfig() {
        try {
            FileUtils.copyDirectory(getDefaultOptionsFolder(), new File(Minecraft.getMinecraft().mcDataDir, "config"), file -> !file.getName().equals("options.txt")
                    && !file.getName().equals("optionsof.txt")
                    && !file.getName().equals("keybindings.txt")
                    && !file.getName().equals("defaultoptions")
                    && !file.getName().equals("servers.dat"));
            return true;
        } catch (IOException e) {
            logger.error(e);
            return false;
        }
    }

    private static boolean applyDefaultOptions() {
        File defaultOptionsFile = new File(getDefaultOptionsFolder(), "options.txt");
        if (defaultOptionsFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(defaultOptionsFile));
                 PrintWriter writer = new PrintWriter(new FileWriter(new File(Minecraft.getMinecraft().mcDataDir, "options.txt")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("key_")) {
                        continue;
                    }
                    writer.println(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }

    private static boolean applyDefaultOptionsOptiFine() {
        File defaultOptionsFile = new File(getDefaultOptionsFolder(), "optionsof.txt");
        if (defaultOptionsFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(defaultOptionsFile));
                 PrintWriter writer = new PrintWriter(new FileWriter(new File(Minecraft.getMinecraft().mcDataDir, "optionsof.txt")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.println(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }
    
    private static boolean applyDefaultOptionsShaders() {
        File defaultOptionsFile = new File(getDefaultOptionsFolder(), "optionsshaders.txt");
        if (defaultOptionsFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(defaultOptionsFile));
                 PrintWriter writer = new PrintWriter(new FileWriter(new File(Minecraft.getMinecraft().mcDataDir, "optionsshaders.txt")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.println(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }

    public boolean saveDefaultOptionsOptiFine() {
        if (!FMLClientHandler.instance().hasOptifine()) {
            return true;
        }
        Minecraft.getMinecraft().gameSettings.saveOptions();
        try (PrintWriter writer = new PrintWriter(new FileWriter(new File(getDefaultOptionsFolder(), "optionsof.txt")));
             BufferedReader reader = new BufferedReader(new FileReader(new File(Minecraft.getMinecraft().mcDataDir, "optionsof.txt")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.println(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
    
    public boolean saveDefaultOptionsShaders() {
        if (!FMLClientHandler.instance().hasOptifine()) {
            return true;
        }
        Minecraft.getMinecraft().gameSettings.saveOptions();
        try (PrintWriter writer = new PrintWriter(new FileWriter(new File(getDefaultOptionsFolder(), "optionsshaders.txt")));
             BufferedReader reader = new BufferedReader(new FileReader(new File(Minecraft.getMinecraft().mcDataDir, "optionsshaders.txt")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.println(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public boolean saveDefaultOptions() {
        Minecraft.getMinecraft().gameSettings.saveOptions();
        try (PrintWriter writer = new PrintWriter(new FileWriter(new File(getDefaultOptionsFolder(), "options.txt")));
             BufferedReader reader = new BufferedReader(new FileReader(new File(Minecraft.getMinecraft().mcDataDir, "options.txt")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("key_")) {
                    continue;
                }
                writer.println(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public boolean saveDefaultServers() {
        File serversDat = new File(Minecraft.getMinecraft().mcDataDir, "servers.dat");
        if (serversDat.exists()) {
            try {
                FileUtils.copyFile(serversDat, new File(getDefaultOptionsFolder(), "servers.dat"));
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        } else {
            return true;
        }
    }

    public boolean saveDefaultMappings() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(new File(getDefaultOptionsFolder(), "keybindings.txt")))) {
            for (KeyBinding keyBinding : Minecraft.getMinecraft().gameSettings.keyBindings) {
                writer.println("key_" + keyBinding.getKeyDescription() + ":" + keyBinding.getKeyCode() + ":" + keyBinding.getKeyModifier().name());
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private Pattern PATTERN = Pattern.compile("key_(.+):([0-9\\-]+)(?::(.+))?");

    public void reloadDefaultMappings() {
        // Clear old values
        defaultKeys.clear();
        knownKeys.clear();

        // Load the default keys from the config
        File defaultKeysFile = new File(getDefaultOptionsFolder(), "keybindings.txt");
        if (defaultKeysFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(defaultKeysFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }

                    Matcher matcher = PATTERN.matcher(line);
                    if (!matcher.matches()) {
                        continue;
                    }

                    try {
                        KeyModifier modifier = matcher.group(3) != null ? KeyModifier.valueFromString(matcher.group(3)) : KeyModifier.NONE;
                        defaultKeys.put(matcher.group(1), new DefaultBinding(Integer.parseInt(matcher.group(2)), modifier));
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                    }
                }
            } catch (java.io.IOException e) {
                e.printStackTrace();
            }
        }

        // Load the known keys from the Minecraft directory
        File knownKeysFile = new File(Minecraft.getMinecraft().mcDataDir, "knownkeys.txt");
        if (knownKeysFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(knownKeysFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty()) {
                        knownKeys.add(line);
                    }
                }
            } catch (java.io.IOException e) {
                e.printStackTrace();
            }
        }

        // Override the default mappings and set the initial key codes, if the key is not known yet
        for (KeyBinding keyBinding : Minecraft.getMinecraft().gameSettings.keyBindings) {
            if (defaultKeys.containsKey(keyBinding.getKeyDescription())) {
                DefaultBinding defaultBinding = defaultKeys.get(keyBinding.getKeyDescription());
                keyBinding.keyCodeDefault = defaultBinding.keyCode;
                ReflectionHelper.setPrivateValue(KeyBinding.class, keyBinding, defaultBinding.modifier, "keyModifier");
                if (!knownKeys.contains(keyBinding.getKeyDescription())) {
                    keyBinding.setKeyModifierAndCode(keyBinding.getKeyModifierDefault(), keyBinding.getKeyCodeDefault());
                    knownKeys.add(keyBinding.getKeyDescription());
                }
            }
        }
        KeyBinding.resetKeyBindingArrayAndHash();

        // Save the updated known keys to the knownkeys.txt file in the Minecraft directory
        try (PrintWriter writer = new PrintWriter(new FileWriter(new File(Minecraft.getMinecraft().mcDataDir, "knownkeys.txt")))) {
            for (String key : knownKeys) {
                writer.println(key);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static File getDefaultOptionsFolder() {
        File defaultOptions = new File(Minecraft.getMinecraft().mcDataDir, "config/defaultoptions");
        //noinspection ResultOfMethodCallIgnored
        defaultOptions.mkdirs();
        return defaultOptions;
    }
}
