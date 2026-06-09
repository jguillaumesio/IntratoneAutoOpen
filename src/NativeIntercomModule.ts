import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export interface Spec extends TurboModule {
  // Configuration
  setTargetNumber(phoneNumber: string): void;
  setExpectedCode(code: string): void;
  setTriggerKey(key: string): void;

  // Lifecycle
  startWatching(): void;
  stopWatching(): void;

  // Manual trigger (iOS fallback)
  pressTriggerKey(): void;

  // State query
  isWatching(): Promise<boolean>;

  // Event emitter support
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('IntercomModule');
