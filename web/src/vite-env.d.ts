/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_PUBLIC_APP_URL?: string;
  readonly VITE_SPEAKING_RELEASE_MS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
