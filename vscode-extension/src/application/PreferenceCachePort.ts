import { DEFAULT_REFRESH_INTERVAL_SECONDS } from "../domain/Constants";
import { LastKnownDisplayCache } from "../domain/LastKnownDisplayCache";

export interface PreferenceCachePort {
  getCliExecutableOverride(): string | undefined;
  setCliExecutableOverride(path: string | undefined): void;
  getRefreshIntervalSeconds(): number;
  setRefreshIntervalSeconds(seconds: number): void;
  getLastKnownDisplayCache(): LastKnownDisplayCache | undefined;
  setLastKnownDisplayCache(cache: LastKnownDisplayCache | undefined): void;
}

export function defaultRefreshIntervalSeconds(): number {
  return DEFAULT_REFRESH_INTERVAL_SECONDS;
}
