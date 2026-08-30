import * as fs from "fs";
import * as path from "path";
import { PreferenceCachePort } from "../../application/PreferenceCachePort";
import { StatusRepository } from "../../application/StatusRepository";
import {
  DEFAULT_CLI_TIMEOUT_MS,
  DEFAULT_STDERR_LIMIT_BYTES,
  DEFAULT_STDOUT_LIMIT_BYTES,
} from "../../domain/Constants";
import { SkillBillStatusOutcome, UnavailableReason } from "../../domain/SkillBillStatusOutcome";
import { StatusClock } from "../../domain/StatusClock";
import { CliExecutableResolution, resolveCliExecutable } from "./CliExecutableResolver";
import { mapIdeStatusJson } from "./IdeStatusJsonMapper";
import { BoundedProcessResult, ProcessRunner, ProcessSpec } from "./ProcessRunner";

export const MISSING_EXECUTABLE_SUMMARY =
  "Skill Bill CLI not found — set skillBill.cliPath in settings";

export const MISCONFIGURED_EXECUTABLE_SUMMARY =
  "Skill Bill CLI path override is not usable — check skillBill.cliPath in settings";

export class CliSkillBillStatusRepository implements StatusRepository {
  constructor(
    private readonly preferences: PreferenceCachePort,
    private readonly processRunner: ProcessRunner,
    private readonly clock: StatusClock = StatusClock.system(),
    private readonly executableResolver: () => CliExecutableResolution = () =>
      resolveCliExecutable(this.preferences),
    private readonly timeoutMs: number = DEFAULT_CLI_TIMEOUT_MS,
    private readonly stdoutLimitBytes: number = DEFAULT_STDOUT_LIMIT_BYTES,
    private readonly stderrLimitBytes: number = DEFAULT_STDERR_LIMIT_BYTES,
  ) {}

  async fetchStatus(projectRoot: string): Promise<SkillBillStatusOutcome> {
    const observedAt = this.clock.now();
    const resolution = this.executableResolver();
    if (resolution.kind === "missing") {
      return {
        kind: "unavailable",
        observedAt,
        summary: MISSING_EXECUTABLE_SUMMARY,
        reasonCode: UnavailableReason.MISSING_EXECUTABLE,
        diagnostic: { reasonCode: "missing_executable" },
      };
    }
    if (resolution.kind === "misconfigured") {
      return {
        kind: "unavailable",
        observedAt,
        summary: MISCONFIGURED_EXECUTABLE_SUMMARY,
        reasonCode: UnavailableReason.MISCONFIGURED,
        diagnostic: { reasonCode: "misconfigured_executable" },
      };
    }

    let canonicalRoot: string;
    try {
      canonicalRoot = fs.realpathSync(path.resolve(projectRoot));
    } catch {
      return {
        kind: "unavailable",
        observedAt,
        summary: "Project root is not a usable path",
        reasonCode: UnavailableReason.INVALID_REPOSITORY_INPUT,
        diagnostic: { reasonCode: "invalid_repository_input" },
      };
    }

    let result: BoundedProcessResult;
    try {
      const spec: ProcessSpec = {
        command: [
          resolution.path,
          "work",
          "status",
          "--repo-root",
          canonicalRoot,
          "--format",
          "json",
        ],
        timeoutMs: this.timeoutMs,
        stdoutLimitBytes: this.stdoutLimitBytes,
        stderrLimitBytes: this.stderrLimitBytes,
      };
      result = await this.processRunner.runCoalesced(spec);
    } catch {
      return {
        kind: "unavailable",
        observedAt,
        summary: "Skill Bill status command failed to start",
        reasonCode: UnavailableReason.PROCESS_FAILURE,
        diagnostic: { reasonCode: "process_start_failure" },
      };
    }

    if (result.cancelled) {
      return {
        kind: "unavailable",
        observedAt,
        summary: "Skill Bill status poll cancelled",
        reasonCode: UnavailableReason.CANCELLED,
        diagnostic: { cancelled: true, reasonCode: "cancelled" },
      };
    }
    if (result.timedOut) {
      return {
        kind: "unavailable",
        observedAt,
        summary: "Skill Bill status poll timed out",
        reasonCode: UnavailableReason.TIMEOUT,
        diagnostic: { timedOut: true, reasonCode: "timeout" },
      };
    }
    return mapIdeStatusJson(result.stdout, observedAt, result.exitCode);
  }
}
