import { PreferenceCachePort } from "../../application/PreferenceCachePort";

export class FakePreferenceCache implements PreferenceCachePort {
  constructor(public refreshIntervalSeconds = 60) {}

  getCliExecutableOverride(): string | undefined {
    return undefined;
  }

  setCliExecutableOverride(): void {}

  getRefreshIntervalSeconds(): number {
    return this.refreshIntervalSeconds;
  }

  setRefreshIntervalSeconds(): void {}

  getLastKnownDisplayCache() {
    return undefined;
  }

  setLastKnownDisplayCache(): void {}
}
