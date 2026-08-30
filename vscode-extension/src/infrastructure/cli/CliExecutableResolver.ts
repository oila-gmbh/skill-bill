import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import { PreferenceCachePort } from "../../application/PreferenceCachePort";

export type CliExecutableResolution =
  | { kind: "found"; path: string; source: CliExecutableSource }
  | { kind: "missing" }
  | { kind: "misconfigured" };

export enum CliExecutableSource {
  OVERRIDE = "OVERRIDE",
  SEARCH_PATH = "SEARCH_PATH",
  INSTALL_DIRECTORY = "INSTALL_DIRECTORY",
}

export interface CliEnvironment {
  value(name: string): string | undefined;
}

export const EXECUTABLE_NAME = "skill-bill";

const PATH_VARIABLE = "PATH";
const BIN_DIR_VARIABLE = "SKILL_BILL_BIN_DIR";
const HOME_VARIABLE = "HOME";

export function resolveCliExecutable(
  preferences: PreferenceCachePort,
  environment: CliEnvironment = platformEnvironment(),
): CliExecutableResolution {
  return resolveOverride(preferences.getCliExecutableOverride(), environment);
}

export function resolveOverride(
  rawOverride: string | undefined,
  environment: CliEnvironment = platformEnvironment(),
): CliExecutableResolution {
  const override = rawOverride?.trim();
  if (override) {
    let candidate: string;
    try {
      candidate = path.resolve(override);
    } catch {
      return { kind: "misconfigured" };
    }
    if (isRunnable(candidate)) {
      return { kind: "found", path: candidate, source: CliExecutableSource.OVERRIDE };
    }
    return { kind: "misconfigured" };
  }
  const onPath = findOnPath(EXECUTABLE_NAME, environment);
  if (onPath) {
    return { kind: "found", path: onPath, source: CliExecutableSource.SEARCH_PATH };
  }
  const inInstall = findInInstallDirectories(EXECUTABLE_NAME, environment);
  if (inInstall) {
    return { kind: "found", path: inInstall, source: CliExecutableSource.INSTALL_DIRECTORY };
  }
  return { kind: "missing" };
}

export function findOnPath(name: string, environment: CliEnvironment = platformEnvironment()): string | undefined {
  for (const directory of splitSearchPath(environment.value(PATH_VARIABLE))) {
    const candidate = runnableIn(directory, name);
    if (candidate) {
      return candidate;
    }
  }
  return undefined;
}

export function installDirectories(environment: CliEnvironment = platformEnvironment()): string[] {
  const entries: string[] = [];
  const binDir = environment.value(BIN_DIR_VARIABLE)?.trim();
  if (binDir) {
    entries.push(binDir);
  }
  const home = environment.value(HOME_VARIABLE)?.trim();
  if (home) {
    entries.push(path.join(home, ".local", "bin"));
  }
  return [...new Set(entries)];
}

export function platformEnvironment(): CliEnvironment {
  return {
    value(name: string): string | undefined {
      if (name === PATH_VARIABLE) {
        return mergeSearchPaths(process.env.PATH, process.env.Path);
      }
      if (name === HOME_VARIABLE) {
        return process.env.HOME ?? process.env.USERPROFILE ?? os.homedir();
      }
      return process.env[name];
    },
  };
}

function findInInstallDirectories(name: string, environment: CliEnvironment): string | undefined {
  for (const directory of installDirectories(environment)) {
    const candidate = runnableIn(directory, name);
    if (candidate) {
      return candidate;
    }
  }
  return undefined;
}

function runnableIn(directory: string, name: string): string | undefined {
  let candidate: string;
  try {
    candidate = path.join(directory, name);
  } catch {
    return undefined;
  }
  return isRunnable(candidate) ? candidate : undefined;
}

function isRunnable(filePath: string): boolean {
  try {
    const stat = fs.statSync(filePath);
    if (!stat.isFile()) {
      return false;
    }
    if (process.platform === "win32") {
      return true;
    }
    fs.accessSync(filePath, fs.constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

function mergeSearchPaths(...paths: (string | undefined)[]): string | undefined {
  const entries = paths.flatMap((raw) => splitSearchPath(raw));
  const distinct = [...new Set(entries)];
  return distinct.length > 0 ? distinct.join(path.delimiter) : undefined;
}

function splitSearchPath(raw: string | undefined): string[] {
  if (!raw) {
    return [];
  }
  return raw
    .split(path.delimiter)
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
}
