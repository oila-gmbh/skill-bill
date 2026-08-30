import { Readable } from "node:stream";
import { ProcessFactory, ProcessHandle } from "../../infrastructure/cli/ProcessRunner";

export class ScriptedProcessFactory implements ProcessFactory {
  readonly commands: string[][] = [];
  private released = false;

  constructor(
    private readonly exitCode = 0,
    private readonly stdout = "",
    private readonly hold = false,
  ) {}

  release(): void {
    this.released = true;
  }

  start(command: string[], _workingDirectory?: string): ProcessHandle {
    this.commands.push(command);
    const factory = this;
    let finished = !this.hold;

    return {
      get stdout() {
        return readableFromString(factory.stdout);
      },
      get stderr() {
        return readableFromString("");
      },
      waitFor(timeoutMs: number): Promise<boolean> {
        return new Promise((resolve) => {
          if (finished) {
            resolve(true);
            return;
          }
          const started = Date.now();
          const tick = (): void => {
            if (finished || factory.released) {
              finished = true;
              resolve(true);
              return;
            }
            if (Date.now() - started >= timeoutMs) {
              resolve(false);
              return;
            }
            setTimeout(tick, 10);
          };
          tick();
        });
      },
      destroyForcibly(): void {
        finished = true;
      },
      exitValue(): number {
        return factory.exitCode;
      },
      isAlive(): boolean {
        return !finished;
      },
    };
  }
}

function readableFromString(value: string): NodeJS.ReadableStream {
  return Readable.from([Buffer.from(value, "utf8")]);
}
