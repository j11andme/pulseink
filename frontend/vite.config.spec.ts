// @vitest-environment node

import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  ROOT_ENV_DIRECTORY,
  resolveDevServerSettings
} from "./vite.config";

describe("Vite root environment configuration", () => {
  it("loads environment files from the repository root", () => {
    expect(ROOT_ENV_DIRECTORY).toBe(fileURLToPath(new URL("..", import.meta.url)));
  });

  it("uses safe local defaults", () => {
    expect(resolveDevServerSettings({})).toEqual({
      port: 5173,
      backendUrl: "http://localhost:8080"
    });
  });

  it("selects only the frontend port and backend URL from root environment values", () => {
    const settings = resolveDevServerSettings({
      FRONTEND_PORT: " 5180 ",
      PULSEINK_BACKEND_URL: " http://127.0.0.1:8099/ ",
      ARK_API_KEY: "must-never-enter-vite-config"
    });

    expect(settings).toEqual({
      port: 5180,
      backendUrl: "http://127.0.0.1:8099"
    });
    expect(JSON.stringify(settings)).not.toContain("must-never-enter-vite-config");
  });

  it.each([
    [{ FRONTEND_PORT: "0" }, "FRONTEND_PORT"],
    [{ FRONTEND_PORT: "not-a-port" }, "FRONTEND_PORT"],
    [{ PULSEINK_BACKEND_URL: "file:///tmp/backend" }, "PULSEINK_BACKEND_URL"]
  ])("rejects invalid public development settings", (environment, settingName) => {
    expect(() => resolveDevServerSettings(environment)).toThrow(settingName);
  });
});
