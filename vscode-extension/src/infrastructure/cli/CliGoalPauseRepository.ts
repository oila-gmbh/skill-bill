import * as fs from "fs";
import * as path from "path";
import { GoalPauseOutcome, GoalPauseRepository } from "../../application/GoalPauseRepository";
import { PreferenceCachePort } from "../../application/PreferenceCachePort";
import {
  DEFAULT_CLI_TIMEOUT_MS,
  DEFAULT_STDERR_LIMIT_BYTES,
  DEFAULT_STDOUT_LIMIT_BYTES,
  GOAL_PAUSE_VERB,
  REPO_ROOT_OPTION,
} from "../../domain/Constants";
import { CliExecutableResolution, resolveCliExecutable } from "./CliExecutableResolver";
import { BoundedProcessResult, ProcessRunner, ProcessSpec } from "./ProcessRunner";

export class CliGoalPauseRepository implements GoalPauseRepository {
  constructor(
    private readonly preferences: PreferenceCachePort,
    private readonly processRunner: ProcessRunner,
    private readonly executableResolver: () => CliExecutableResolution = () =>
      resolveCliExecutable(this.preferences),
    private readonly timeoutMs: number = DEFAULT_CLI_TIMEOUT_MS,
    private readonly stdoutLimitBytes: number = DEFAULT_STDOUT_LIMIT_BYTES,
    private readonly stderrLimitBytes: number = DEFAULT_STDERR_LIMIT_BYTES,
  ) {}

  async requestPause(projectRoot: string, issueKey: string): Promise<GoalPauseOutcome> {
    const key = issueKey.trim();
    if (!key) {
      return { kind: "failed", summary: "No issue key to pause" };
    }
    const resolution = this.executableResolver();
    if (resolution.kind === "missing") {
      return { kind: "failed", summary: "Skill Bill CLI executable not found" };
    }
    if (resolution.kind === "misconfigured") {
      return { kind: "failed", summary: "Skill Bill CLI executable override is not usable" };
    }

    let canonicalRoot: string;
    try {
      canonicalRoot = fs.realpathSync(path.resolve(projectRoot));
    } catch {
      return { kind: "failed", summary: "Project root is not a usable path" };
    }

    let result: BoundedProcessResult;
    try {
      const spec: ProcessSpec = {
        command: [resolution.path, ...GOAL_PAUSE_VERB, key, REPO_ROOT_OPTION, canonicalRoot],
        timeoutMs: this.timeoutMs,
        stdoutLimitBytes: this.stdoutLimitBytes,
        stderrLimitBytes: this.stderrLimitBytes,
      };
      result = await this.processRunner.runCoalesced(spec);
    } catch {
      return { kind: "failed", summary: "Pause request failed to start" };
    }

    if (result.cancelled) {
      return { kind: "failed", summary: "Pause request cancelled" };
    }
    if (result.timedOut) {
      return { kind: "failed", summary: "Pause request timed out" };
    }
    if (result.exitCode === 0) {
      return { kind: "requested" };
    }
    return { kind: "failed", summary: "Skill Bill declined the pause request" };
  }
}
