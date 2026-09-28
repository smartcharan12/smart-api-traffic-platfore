package com.smartapi.gateway.service;

/**
 * Published whenever services or instances are created, deleted or change status. The gateway's
 * discovery cache listens for it so routing sees registry changes immediately (in this gateway
 * instance; other instances see them when their cache entry expires).
 */
public record RegistryChangedEvent(String reason) {
}
