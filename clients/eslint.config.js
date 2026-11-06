import js from "@eslint/js";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["**/dist/**", "**/coverage/**", "**/node_modules/**", "node/**", "**/generated/**"] },
  js.configs.recommended,
  ...tseslint.configs.strict,
);
