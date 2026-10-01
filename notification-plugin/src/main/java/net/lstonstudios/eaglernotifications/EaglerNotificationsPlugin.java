package net.lstonstudios.eaglernotifications;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;

public final class EaglerNotificationsPlugin {
    private final ProxyServer proxy;

    @Inject
    public EaglerNotificationsPlugin(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        proxy.getCommandManager().register("eaglernotify", new EaglerNotifyCommand(), "enotify");
    }
}