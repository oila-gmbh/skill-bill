import * as vscode from "vscode";
import { StatusCompositionRoot } from "./composition/StatusCompositionRoot";
import { StatusBarController } from "./ui/StatusBarController";

const workspaceRoots = new Map<string, StatusCompositionRoot>();
const controllers = new Map<string, StatusBarController>();

export function activate(context: vscode.ExtensionContext): void {
  const showDetails = vscode.commands.registerCommand(
    "skill-bill-status.showDetails",
    (workspaceRootKey?: string) => {
      const key =
        workspaceRootKey ?? vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
      if (!key) {
        void vscode.window.showWarningMessage("Open a workspace folder to view Skill Bill status.");
        return;
      }
      controllers.get(key)?.showDetails();
    },
  );
  context.subscriptions.push(showDetails);

  const ensureController = (folder: vscode.WorkspaceFolder): StatusBarController => {
    const key = folder.uri.fsPath;
    let controller = controllers.get(key);
    if (!controller) {
      let root = workspaceRoots.get(key);
      if (!root) {
        root = StatusCompositionRoot.create(context, key);
        workspaceRoots.set(key, root);
        context.subscriptions.push({ dispose: () => root?.dispose() });
      }
      controller = new StatusBarController(root.viewModel, key);
      controllers.set(key, controller);
      context.subscriptions.push(controller);
    }
    return controller;
  };

  if (vscode.workspace.workspaceFolders) {
    for (const folder of vscode.workspace.workspaceFolders) {
      ensureController(folder);
    }
  }

  const folderListener = vscode.workspace.onDidChangeWorkspaceFolders((event) => {
    for (const folder of event.removed) {
      const key = folder.uri.fsPath;
      controllers.get(key)?.dispose();
      controllers.delete(key);
      workspaceRoots.get(key)?.dispose();
      workspaceRoots.delete(key);
    }
    for (const folder of event.added) {
      ensureController(folder);
    }
  });
  context.subscriptions.push(folderListener);
}

export function deactivate(): void {
  for (const controller of controllers.values()) {
    controller.dispose();
  }
  controllers.clear();
  for (const root of workspaceRoots.values()) {
    root.dispose();
  }
  workspaceRoots.clear();
}
