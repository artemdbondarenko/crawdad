// Generates TypeScript types of the protocol messages from the JSON Schema in protocol/.
// Run through `pnpm generate`; the Maven build runs it before the clients are linted and checked.
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { compileFromFile } from "json-schema-to-typescript";

const schema = join(import.meta.dirname, "../../../protocol/schema/crawdad-protocol.schema.json");
const outputDir = join(import.meta.dirname, "../src/generated");

const types = await compileFromFile(schema, {
  bannerComment:
    "// Generated from protocol/schema/crawdad-protocol.schema.json by `pnpm generate`. Do not edit.",
  additionalProperties: false,
});

mkdirSync(outputDir, { recursive: true });
writeFileSync(join(outputDir, "protocol.ts"), types);
console.log(`Protocol types written to ${join(outputDir, "protocol.ts")}`);
