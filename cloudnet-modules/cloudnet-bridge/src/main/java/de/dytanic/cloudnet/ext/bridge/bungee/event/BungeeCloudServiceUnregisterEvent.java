package de.dytanic.cloudnet.ext.bridge.bungee.event;

import de.dytanic.cloudnet.driver.service.ServiceInfoSnapshot;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class BungeeCloudServiceUnregisterEvent extends BungeeCloudNetEvent {

    @Getter
    private final ServiceInfoSnapshot serviceInfoSnapshot;
}