package gay.ampflower.junkie;// Created 2023-23-01T02:53:19

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.gudenau.minecraft.asm.impl.Bootstrap;

/**
 * Secondary, fallback, bootstrapping route for Bytecode Junkie.
 *
 * @author Ampflower
 * @since 0.3.2
 **/
public class PreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        Bootstrap.setup();
    }
}
