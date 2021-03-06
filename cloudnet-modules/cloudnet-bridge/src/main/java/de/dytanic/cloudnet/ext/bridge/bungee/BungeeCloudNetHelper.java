package de.dytanic.cloudnet.ext.bridge.bungee;

import de.dytanic.cloudnet.common.Validate;
import de.dytanic.cloudnet.common.collection.Iterables;
import de.dytanic.cloudnet.common.collection.Maps;
import de.dytanic.cloudnet.driver.network.HostAndPort;
import de.dytanic.cloudnet.driver.service.ServiceEnvironmentType;
import de.dytanic.cloudnet.driver.service.ServiceInfoSnapshot;
import de.dytanic.cloudnet.ext.bridge.*;
import de.dytanic.cloudnet.ext.bridge.player.NetworkConnectionInfo;
import de.dytanic.cloudnet.ext.bridge.player.NetworkServiceInfo;
import de.dytanic.cloudnet.wrapper.Wrapper;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public final class BungeeCloudNetHelper {

    public static final Map<String, ServiceInfoSnapshot> SERVER_TO_SERVICE_INFO_SNAPSHOT_ASSOCIATION = Maps.newConcurrentHashMap();

    private BungeeCloudNetHelper()
    {
        throw new UnsupportedOperationException();
    }

    public static void addItemToBungeeCordListenerPrioritySystem(ServiceInfoSnapshot serviceInfoSnapshot, String name)
    {
        Validate.checkNotNull(name);
        Validate.checkNotNull(serviceInfoSnapshot);

        handleWithListenerInfoServerPriority(new Consumer<Collection<String>>() {
            @Override
            public void accept(Collection<String> collection)
            {
                for (ProxyFallbackConfiguration bungeeFallbackConfiguration : BridgeConfigurationProvider.load().getBungeeFallbackConfigurations())
                    if (bungeeFallbackConfiguration != null && bungeeFallbackConfiguration.getFallbacks() != null &&
                        bungeeFallbackConfiguration.getTargetGroup() != null && Iterables.contains(bungeeFallbackConfiguration.getTargetGroup(),
                        Wrapper.getInstance().getCurrentServiceInfoSnapshot().getConfiguration().getGroups()))
                        if (!collection.contains(name) && bungeeFallbackConfiguration.getDefaultFallbackTask().equals(serviceInfoSnapshot.getServiceId().getTaskName()))
                            collection.add(name);
            }
        });
    }

    public static void removeItemToBungeeCordListenerPrioritySystem(ServiceInfoSnapshot serviceInfoSnapshot, String name)
    {
        Validate.checkNotNull(name);

        handleWithListenerInfoServerPriority(new Consumer<Collection<String>>() {
            @Override
            public void accept(Collection<String> collection)
            {
                collection.remove(name);
            }
        });
    }

    public static void handleWithListenerInfoServerPriority(Consumer<Collection<String>> listenerInfoConsumer)
    {
        try
        {
            Method method = ListenerInfo.class.getMethod("getServerPriority");
            method.setAccessible(true);

            for (ListenerInfo listenerInfo : ProxyServer.getInstance().getConfigurationAdapter().getListeners())
            {
                Object instance = method.invoke(listenerInfo);
                listenerInfoConsumer.accept((Collection<String>) instance);
            }

        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored)
        {
        } catch (Exception ex)
        {
            ex.printStackTrace();
        }
    }

    public static boolean isOnAFallbackInstance(ProxiedPlayer proxiedPlayer)
    {
        ServiceInfoSnapshot serviceInfoSnapshot = SERVER_TO_SERVICE_INFO_SNAPSHOT_ASSOCIATION.get(proxiedPlayer.getServer().getInfo().getName());

        for (ProxyFallbackConfiguration bungeeFallbackConfiguration : BridgeConfigurationProvider.load().getBungeeFallbackConfigurations())
            if (bungeeFallbackConfiguration.getTargetGroup() != null && Iterables.contains(
                bungeeFallbackConfiguration.getTargetGroup(),
                Wrapper.getInstance().getCurrentServiceInfoSnapshot().getConfiguration().getGroups()
            ))
            {
                for (ProxyFallback bungeeFallback : bungeeFallbackConfiguration.getFallbacks())
                    if (bungeeFallback.getTask() != null && serviceInfoSnapshot.getServiceId().getTaskName().equals(bungeeFallback.getTask()))
                        return true;
            }

        return false;
    }

    public static String filterServiceForProxiedPlayer(ProxiedPlayer proxiedPlayer, String currentServer)
    {
        for (ProxyFallbackConfiguration proxyFallbackConfiguration : BridgeConfigurationProvider.load().getBungeeFallbackConfigurations())
            if (proxyFallbackConfiguration.getTargetGroup() != null && Iterables.contains(
                proxyFallbackConfiguration.getTargetGroup(),
                Wrapper.getInstance().getCurrentServiceInfoSnapshot().getConfiguration().getGroups()
            ))
            {
                List<ProxyFallback> proxyFallbacks = Iterables.newArrayList(proxyFallbackConfiguration.getFallbacks());
                Collections.sort(proxyFallbacks);

                String server = null;

                for (ProxyFallback proxyFallback : proxyFallbacks)
                {
                    if (proxyFallback.getTask() != null) continue;
                    if (proxyFallback.getPermission() != null && !proxiedPlayer.hasPermission(proxyFallback.getPermission()))
                        continue;

                    List<Map.Entry<String, ServiceInfoSnapshot>> entries = getFilteredEntries(proxyFallback.getTask(), currentServer);

                    if (entries.size() == 0) continue;

                    server = entries.get(new Random().nextInt(entries.size())).getKey();
                }

                if (server == null)
                {
                    List<Map.Entry<String, ServiceInfoSnapshot>> entries = getFilteredEntries(proxyFallbackConfiguration.getDefaultFallbackTask(), currentServer);

                    if (entries.size() > 0)
                        server = entries.get(new Random().nextInt(entries.size())).getKey();
                }

                return server;
            }

        return null;
    }

    private static List<Map.Entry<String, ServiceInfoSnapshot>> getFilteredEntries(String task, String currentServer)
    {
        return Iterables.filter(
            SERVER_TO_SERVICE_INFO_SNAPSHOT_ASSOCIATION.entrySet(), new Predicate<Map.Entry<String, ServiceInfoSnapshot>>() {

                @Override
                public boolean test(Map.Entry<String, ServiceInfoSnapshot> stringServiceInfoSnapshotEntry)
                {
                    if (currentServer != null && currentServer.equalsIgnoreCase(stringServiceInfoSnapshotEntry.getKey()))
                        return false;

                    return task.equals(stringServiceInfoSnapshotEntry.getValue().getServiceId().getTaskName());
                }
            });
    }

    public static boolean isServiceEnvironmentTypeProvidedForBungeeCord(ServiceInfoSnapshot serviceInfoSnapshot)
    {
        Validate.checkNotNull(serviceInfoSnapshot);
        return serviceInfoSnapshot.getServiceId().getEnvironment().isMinecraftJavaServer();
    }

    public static void initProperties(ServiceInfoSnapshot serviceInfoSnapshot)
    {
        Validate.checkNotNull(serviceInfoSnapshot);

        serviceInfoSnapshot.getProperties()
            .append("Online", true)
            .append("Version", ProxyServer.getInstance().getVersion())
            .append("Game-Version", ProxyServer.getInstance().getGameVersion())
            .append("Online-Count", ProxyServer.getInstance().getOnlineCount())
            .append("Channels", ProxyServer.getInstance().getChannels())
            .append("BungeeCord-Name", ProxyServer.getInstance().getName())
            .append("Players", Iterables.map(ProxyServer.getInstance().getPlayers(), new Function<ProxiedPlayer, BungeeCloudNetPlayerInfo>() {
                @Override
                public BungeeCloudNetPlayerInfo apply(ProxiedPlayer proxiedPlayer)
                {
                    return new BungeeCloudNetPlayerInfo(
                        getUniqueIdOfPlayer(proxiedPlayer),
                        proxiedPlayer.getName(),
                        proxiedPlayer.getServer() != null ? proxiedPlayer.getServer().getInfo().getName() : null,
                        proxiedPlayer.getPing(),
                        new HostAndPort(proxiedPlayer.getPendingConnection().getAddress())
                    );
                }
            }))
            .append("Plugins", Iterables.map(ProxyServer.getInstance().getPluginManager().getPlugins(), new Function<Plugin, PluginInfo>() {
                @Override
                public PluginInfo apply(Plugin plugin)
                {
                    PluginInfo pluginInfo = new PluginInfo(plugin.getDescription().getName(), plugin.getDescription().getVersion());

                    pluginInfo.getProperties()
                        .append("author", plugin.getDescription().getAuthor())
                        .append("main-class", plugin.getDescription().getMain())
                        .append("depends", plugin.getDescription().getDepends())
                    ;

                    return pluginInfo;
                }
            }))
        ;
    }

    public static NetworkConnectionInfo createNetworkConnectionInfo(PendingConnection pendingConnection)
    {
        Boolean onlineMode = isOnlineModeOfPendingConnection(pendingConnection);
        if (onlineMode == null) onlineMode = true;

        Boolean legacy = isLegacyOfPendingConnection(pendingConnection);
        if (legacy == null) legacy = true;

        return BridgeHelper.createNetworkConnectionInfo(
            getUniqueIdOfPendingConnection(pendingConnection) == null ? UUID.randomUUID() : getUniqueIdOfPendingConnection(pendingConnection),
            pendingConnection.getName(),
            getVersionOfPendingConnection(pendingConnection),
            new HostAndPort(pendingConnection.getAddress()),
            new HostAndPort(pendingConnection.getListener().getHost()),
            onlineMode,
            legacy,
            new NetworkServiceInfo(
                ServiceEnvironmentType.BUNGEECORD,
                Wrapper.getInstance().getServiceId().getUniqueId(),
                Wrapper.getInstance().getServiceId().getName()
            )
        );
    }

    public static UUID getUniqueIdOfPlayer(ProxiedPlayer proxiedPlayer)
    {
        Validate.checkNotNull(proxiedPlayer);

        UUID uniqueId = null;

        try
        {
            Method method = ProxiedPlayer.class.getMethod("getUniqueId");
            method.setAccessible(true);
            uniqueId = (UUID) method.invoke(proxiedPlayer);

        } catch (Exception ignored)
        {
        }

        return uniqueId;
    }

    public static UUID getUniqueIdOfPendingConnection(PendingConnection connection)
    {
        Validate.checkNotNull(connection);

        UUID uniqueId = null;

        try
        {
            Method method = PendingConnection.class.getMethod("getUniqueId");
            method.setAccessible(true);
            uniqueId = (UUID) method.invoke(connection);

        } catch (Exception ignored)
        {
        }

        return uniqueId;
    }

    public static Boolean isOnlineModeOfPendingConnection(PendingConnection connection)
    {
        Validate.checkNotNull(connection);

        Boolean bool = null;

        try
        {
            Method method = PendingConnection.class.getMethod("isOnlineMode");
            method.setAccessible(true);
            bool = (Boolean) method.invoke(connection);

        } catch (Exception ignored)
        {
        }

        return bool;
    }

    public static Boolean isLegacyOfPendingConnection(PendingConnection connection)
    {
        Validate.checkNotNull(connection);

        Boolean bool = null;

        try
        {
            Method method = PendingConnection.class.getMethod("isLegacy");
            method.setAccessible(true);
            bool = (Boolean) method.invoke(connection);

        } catch (Exception ignored)
        {
        }

        return bool;
    }

    public static int getVersionOfPendingConnection(PendingConnection pendingConnection)
    {
        Validate.checkNotNull(pendingConnection);

        try
        {

            Method method = PendingConnection.class.getDeclaredMethod("getVersion");
            method.setAccessible(true);
            return (int) method.invoke(pendingConnection);

        } catch (Exception ignored)
        {
        }

        return -1;
    }

    public static ServerInfo createServerInfo(String name, InetSocketAddress address)
    {
        Validate.checkNotNull(name);
        Validate.checkNotNull(address);

        Class<ProxyServer> proxyServerClass = ProxyServer.class;
        Method method;

        try //1.4.7
        {
            method = proxyServerClass.getMethod("constructServerInfo", String.class, InetSocketAddress.class);
            method.setAccessible(true);
            return (ServerInfo) method.invoke(ProxyServer.getInstance(), name, address);
        } catch (Exception ignored)
        {
        }

        try //with restricted
        {
            method = proxyServerClass.getMethod("constructServerInfo", String.class, InetSocketAddress.class, boolean.class);
            method.setAccessible(true);
            return (ServerInfo) method.invoke(ProxyServer.getInstance(), name, address, false);
        } catch (Exception ignored)
        {
        }

        try //with motd
        {
            method = proxyServerClass.getMethod("constructServerInfo", String.class, InetSocketAddress.class, String.class, boolean.class);
            method.setAccessible(true);
            return (ServerInfo) method.invoke(ProxyServer.getInstance(), name, address, "CloudNet provided serverInfo", false);
        } catch (Exception ignored)
        {
        }

        return null;
    }
}