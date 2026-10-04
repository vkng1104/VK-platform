import "server-only";

import path from "node:path";

export function repositoryPath(...parts: string[]): string {
  const workingDirectory = process.cwd();
  const repositoryRoot =
    path.basename(workingDirectory) === "web" &&
    path.basename(path.dirname(workingDirectory)) === "apps"
      ? path.resolve(workingDirectory, "../..")
      : workingDirectory;

  return path.join(repositoryRoot, ...parts);
}
