import vue from "@vitejs/plugin-vue";
import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv } from "vite";

const DEFAULT_FRONTEND_PORT = 5173;
const DEFAULT_BACKEND_URL = "http://localhost:8080";

export const ROOT_ENV_DIRECTORY = fileURLToPath(new URL("..", import.meta.url));

export interface DevServerSettings {
  port: number;
  backendUrl: string;
}

export function resolveDevServerSettings(
  environment: Record<string, string | undefined>
): DevServerSettings {
  const rawPort = environment.FRONTEND_PORT?.trim();
  const port = rawPort ? Number(rawPort) : DEFAULT_FRONTEND_PORT;
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new Error("FRONTEND_PORT must be an integer between 1 and 65535");
  }

  const rawBackendUrl =
    environment.PULSEINK_BACKEND_URL?.trim() || DEFAULT_BACKEND_URL;
  let parsedBackendUrl: URL;
  try {
    parsedBackendUrl = new URL(rawBackendUrl);
  } catch {
    throw new Error("PULSEINK_BACKEND_URL must be a valid HTTP(S) URL");
  }
  if (!["http:", "https:"].includes(parsedBackendUrl.protocol)) {
    throw new Error("PULSEINK_BACKEND_URL must be a valid HTTP(S) URL");
  }

  return {
    port,
    backendUrl: rawBackendUrl.replace(/\/+$/, "")
  };
}

export default defineConfig(({ mode }) => {
  const environment = loadEnv(mode, ROOT_ENV_DIRECTORY, [
    "FRONTEND_",
    "PULSEINK_BACKEND_"
  ]);
  const settings = resolveDevServerSettings(environment);

  return {
    envDir: ROOT_ENV_DIRECTORY,
    plugins: [vue()],
    server: {
      port: settings.port,
      proxy: {
        "/api": {
          target: settings.backendUrl,
          changeOrigin: true
        }
      }
    }
  };
});
