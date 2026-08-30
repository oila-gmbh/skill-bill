import { spawn, ChildProcess } from "child_process";

export interface ProcessSpec {
  command: string[];
  workingDirectory?: string;
  timeoutMs: number;
  stdoutLimitBytes: number;
  stderrLimitBytes: number;
}

export interface BoundedProcessResult {
  exitCode: number | undefined;
  stdout: string;
  stderrTruncated: boolean;
  stdoutTruncated: boolean;
  timedOut: boolean;
  cancelled: boolean;
}

export interface ProcessHandle {
  readonly stdout: NodeJS.ReadableStream;
  readonly stderr: NodeJS.ReadableStream;
  waitFor(timeoutMs: number): Promise<boolean>;
  destroyForcibly(): void;
  exitValue(): number;
  isAlive(): boolean;
}

export interface ProcessFactory {
  start(command: string[], workingDirectory?: string): ProcessHandle;
}

export class RealProcessFactory implements ProcessFactory {
  start(command: string[], workingDirectory?: string): ProcessHandle {
    const child = spawn(command[0], command.slice(1), {
      cwd: workingDirectory,
      stdio: ["ignore", "pipe", "pipe"],
    });
    return new NodeProcessHandle(child);
  }
}

class NodeProcessHandle implements ProcessHandle {
  constructor(private readonly child: ChildProcess) {}

  get stdout(): NodeJS.ReadableStream {
    return this.child.stdout!;
  }

  get stderr(): NodeJS.ReadableStream {
    return this.child.stderr!;
  }

  waitFor(timeoutMs: number): Promise<boolean> {
    return new Promise((resolve) => {
      const timer = setTimeout(() => resolve(false), timeoutMs);
      this.child.once("exit", () => {
        clearTimeout(timer);
        resolve(true);
      });
      this.child.once("error", () => {
        clearTimeout(timer);
        resolve(true);
      });
    });
  }

  destroyForcibly(): void {
    this.child.kill("SIGKILL");
  }

  exitValue(): number {
    return this.child.exitCode ?? 0;
  }

  isAlive(): boolean {
    return this.child.exitCode === null && !this.child.killed;
  }
}

export class ProcessRunner {
  private inFlight: Promise<BoundedProcessResult> | undefined;
  private activeProcess: ProcessHandle | undefined;
  private cancelled = false;

  constructor(private readonly processFactory: ProcessFactory = new RealProcessFactory()) {}

  async runCoalesced(spec: ProcessSpec): Promise<BoundedProcessResult> {
    if (this.inFlight) {
      return this.inFlight;
    }
    const run = this.execute(spec);
    this.inFlight = run;
    try {
      return await run;
    } finally {
      this.inFlight = undefined;
    }
  }

  cancelAll(): void {
    this.cancelled = true;
    this.activeProcess?.destroyForcibly();
    this.activeProcess = undefined;
    this.inFlight = undefined;
  }

  resetCancellation(): void {
    this.cancelled = false;
  }

  private async execute(spec: ProcessSpec): Promise<BoundedProcessResult> {
    if (this.cancelled) {
      return cancelledResult();
    }
    const process = this.processFactory.start(spec.command, spec.workingDirectory);
    this.activeProcess = process;
    try {
      const stdoutChunks: Buffer[] = [];
      let stdoutTruncated = false;
      let stderrTruncated = false;

      const stdoutPromise = readBounded(process.stdout, spec.stdoutLimitBytes, (chunk, truncated) => {
        stdoutChunks.push(chunk);
        if (truncated) {
          stdoutTruncated = true;
        }
      });
      const stderrPromise = readBounded(process.stderr, spec.stderrLimitBytes, (_chunk, truncated) => {
        if (truncated) {
          stderrTruncated = true;
        }
      });

      const deadline = Date.now() + spec.timeoutMs;
      let finished = false;
      while (true) {
        if (this.cancelled) {
          process.destroyForcibly();
          await Promise.all([stdoutPromise, stderrPromise]);
          return {
            exitCode: undefined,
            stdout: "",
            stderrTruncated,
            stdoutTruncated,
            timedOut: false,
            cancelled: true,
          };
        }
        const remaining = deadline - Date.now();
        if (remaining <= 0) {
          break;
        }
        finished = await process.waitFor(Math.min(remaining, 100));
        if (finished) {
          break;
        }
      }
      if (!finished) {
        process.destroyForcibly();
        await Promise.all([stdoutPromise, stderrPromise]);
        return {
          exitCode: undefined,
          stdout: Buffer.concat(stdoutChunks).toString("utf8"),
          stderrTruncated,
          stdoutTruncated,
          timedOut: true,
          cancelled: this.cancelled,
        };
      }
      await Promise.all([stdoutPromise, stderrPromise]);
      if (this.cancelled) {
        return {
          exitCode: undefined,
          stdout: "",
          stderrTruncated,
          stdoutTruncated,
          timedOut: false,
          cancelled: true,
        };
      }
      return {
        exitCode: process.exitValue(),
        stdout: Buffer.concat(stdoutChunks).toString("utf8"),
        stderrTruncated,
        stdoutTruncated,
        timedOut: false,
        cancelled: false,
      };
    } finally {
      if (this.activeProcess === process) {
        this.activeProcess = undefined;
      }
    }
  }
}

function cancelledResult(): BoundedProcessResult {
  return {
    exitCode: undefined,
    stdout: "",
    stderrTruncated: false,
    stdoutTruncated: false,
    timedOut: false,
    cancelled: true,
  };
}

async function readBounded(
  stream: NodeJS.ReadableStream,
  limit: number,
  onChunk: (chunk: Buffer, truncated: boolean) => void,
): Promise<void> {
  let total = 0;
  let truncated = false;
  return new Promise((resolve, reject) => {
    stream.on("data", (data: Buffer) => {
      if (truncated) {
        return;
      }
      const remaining = limit - total;
      if (remaining <= 0) {
        truncated = true;
        onChunk(Buffer.alloc(0), true);
        return;
      }
      const slice = data.subarray(0, remaining);
      total += slice.length;
      onChunk(slice, slice.length < data.length);
      if (slice.length < data.length) {
        truncated = true;
      }
    });
    stream.on("end", resolve);
    stream.on("error", reject);
  });
}
