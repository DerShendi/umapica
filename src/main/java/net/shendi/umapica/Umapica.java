package net.shendi.umapica;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(Umapica.MOD_ID)
public class Umapica {

    public static final String MOD_ID = "umapica";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Umapica(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
    }
}
