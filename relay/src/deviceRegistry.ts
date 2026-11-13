import type { DeviceConnection } from './deviceConnection.js';

/**
 * The live connection of each device, keyed by deviceId. At most one connection per device:
 * the newest wins (docs/PROTOCOL.md §3.1). In-memory only; the relay keeps no persistent state.
 */
export class DeviceRegistry {
  private readonly devices = new Map<string, DeviceConnection>();

  get size(): number {
    return this.devices.size;
  }

  get(deviceId: string): DeviceConnection | undefined {
    return this.devices.get(deviceId);
  }

  /** Makes [connection] the device's live connection; returns the one it replaces, if any. */
  register(connection: DeviceConnection): DeviceConnection | undefined {
    const previous = this.devices.get(connection.deviceId);
    this.devices.set(connection.deviceId, connection);
    return previous === connection ? undefined : previous;
  }

  /** Removes [connection] if it is still the device's live connection (not already replaced). */
  unregister(connection: DeviceConnection): void {
    if (this.devices.get(connection.deviceId) === connection) this.devices.delete(connection.deviceId);
  }

  values(): IterableIterator<DeviceConnection> {
    return this.devices.values();
  }
}
