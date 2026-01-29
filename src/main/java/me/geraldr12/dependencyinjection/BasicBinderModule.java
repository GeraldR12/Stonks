package me.geraldr12.dependencyinjection;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import me.geraldr12.Stonks;
import me.geraldr12.data.repositories.implementations.CompaniesRepository;
import me.geraldr12.data.repositories.implementations.PlayersRepository;
import me.geraldr12.data.repositories.implementations.SignsRepository;
import me.geraldr12.data.sources.yml.implementations.CompaniesYml;
import me.geraldr12.data.sources.yml.implementations.PlayersYml;
import me.geraldr12.data.sources.yml.implementations.SignsYml;
import me.geraldr12.listeners.PlayerJoinListener;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.File;
import java.util.logging.Logger;

public class BasicBinderModule extends AbstractModule {

    private final Stonks plugin;

    public BasicBinderModule(Stonks plugin) {
        this.plugin = plugin;
    }

    public Injector createInjector() {
        return Guice.createInjector(this);
    }

    @Override
    protected void configure() {

        this.bind(Stonks.class).toInstance(plugin);

        this.bind(Server.class).toInstance(plugin.getServer());
        this.bind(PluginDescriptionFile.class).toInstance(plugin.getDescription());

        this.bind(Logger.class).annotatedWith(Names.named("bukkitLogger")).toInstance(plugin.getLogger());
        this.bind(File.class).annotatedWith(Names.named("pluginDataDirectory")).toInstance(plugin.getDataFolder());
        this.bind(FileConfiguration.class).annotatedWith(Names.named("pluginConfig")).toInstance(YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml")));

        // TODO: Change bindings depending on the data source being used
        this.bind(CompaniesRepository.class).toInstance(new CompaniesRepository(plugin.getDataFolder(), new CompaniesYml(plugin.getDataFolder())));
        this.bind(PlayersRepository.class).toInstance(new PlayersRepository(new PlayersYml(plugin.getDataFolder())));
        this.bind(SignsRepository.class).toInstance(new SignsRepository(new SignsYml(plugin.getDataFolder())));

        this.bind(PlayerJoinListener.class);

    }

}
