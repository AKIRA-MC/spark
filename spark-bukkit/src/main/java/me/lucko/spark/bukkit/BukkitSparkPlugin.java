/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.bukkit;

import fr.akiramc.bukkit.command.EngineCommandMap;
import me.lucko.spark.api.Spark;
import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.SparkPlugin;
import me.lucko.spark.common.monitor.ping.PlayerPingProvider;
import me.lucko.spark.common.platform.PlatformInfo;
import me.lucko.spark.common.platform.serverconfig.ServerConfigProvider;
import me.lucko.spark.common.platform.world.WorldInfoProvider;
import me.lucko.spark.common.sampler.ThreadDumper;
import me.lucko.spark.common.sampler.source.ClassSourceLookup;
import me.lucko.spark.common.sampler.source.SourceMetadata;
import me.lucko.spark.common.tick.TickHook;
import me.lucko.spark.common.tick.TickReporter;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Stream;

public class BukkitSparkPlugin extends JavaPlugin implements SparkPlugin {
    private BukkitAudiences audienceFactory;
    private ThreadDumper gameThreadDumper;

    private SparkPlatform platform;

    @Override
    public void onEnable() {
        this.audienceFactory = BukkitAudiences.create(this);
        this.gameThreadDumper = new ThreadDumper.Specific(Thread.currentThread());

        this.platform = new SparkPlatform(this);
        this.platform.enable();

        Bukkit.getScheduler().runTaskLater(this, this::initCommands, 5 * 20L);
    }

    public void initCommands() {
        EngineCommandMap commandMap = (EngineCommandMap) ((CraftServer) getServer()).getCommandMap();
        commandMap.register("", new Command("spark") {
            @Override
            public boolean execute(@NotNull CommandSender commandSender, @NotNull String s, @NotNull String[] strings) {
                platform.executeCommand(new BukkitCommandSender(commandSender, audienceFactory), strings);
                return true;
            }

            @Override
            public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args, @Nullable Location location) throws IllegalArgumentException {
                return tabComplete(sender, alias, args);
            }

            @Override
            public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) throws IllegalArgumentException {
                return platform.tabCompleteCommand(new BukkitCommandSender(sender, audienceFactory), args);
            }
        });
    }

    @Override
    public void onDisable() {
        this.platform.disable();
    }

    @Override
    public String getVersion() {
        return getDescription().getVersion();
    }

    @Override
    public Path getPluginDirectory() {
        return getDataFolder().toPath();
    }

    @Override
    public String getCommandName() {
        return "spark";
    }

    @Override
    public Stream<BukkitCommandSender> getCommandSenders() {
        return Stream.concat(
                getServer().getOnlinePlayers().stream(),
                Stream.of(getServer().getConsoleSender())
        ).map(sender -> new BukkitCommandSender(sender, this.audienceFactory));
    }

    @Override
    public void executeAsync(Runnable task) {
        getServer().getScheduler().runTaskAsynchronously(this, task);
    }

    @Override
    public void executeSync(Runnable task) {
        getServer().getScheduler().runTask(this, task);
    }

    @Override
    public void log(Level level, String msg) {
        getLogger().log(level, msg);
    }

    @Override
    public void log(Level level, String msg, Throwable throwable) {
        getLogger().log(level, msg, throwable);
    }

    @Override
    public ThreadDumper getDefaultThreadDumper() {
        return this.gameThreadDumper;
    }

    @Override
    public TickHook createTickHook() {
        getLogger().info("Using Bukkit scheduler for tick monitoring");
        return new BukkitTickHook(this);
    }

    @Override
    public ClassSourceLookup createClassSourceLookup() {
        return new BukkitClassSourceLookup();
    }

    @Override
    public Collection<SourceMetadata> getKnownSources() {
        return SourceMetadata.gather(
                Arrays.asList(getServer().getPluginManager().getPlugins()),
                Plugin::getName,
                plugin -> plugin.getDescription().getVersion(),
                plugin -> String.join(", ", plugin.getDescription().getAuthors()),
                plugin -> plugin.getDescription().getDescription()
        );
    }

    @Override
    public PlayerPingProvider createPlayerPingProvider() {
        if (BukkitPlayerPingProvider.isSupported()) {
            return new BukkitPlayerPingProvider(getServer());
        } else {
            return null;
        }
    }

    @Override
    public ServerConfigProvider createServerConfigProvider() {
        return new BukkitServerConfigProvider();
    }

    @Override
    public WorldInfoProvider createWorldInfoProvider() {
        return new BukkitWorldInfoProvider(getServer());
    }

    @Override
    public PlatformInfo getPlatformInfo() {
        return new BukkitPlatformInfo(getServer());
    }

    @Override
    public void registerApi(Spark api) {
        getServer().getServicesManager().register(Spark.class, api, this, ServicePriority.Normal);
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
