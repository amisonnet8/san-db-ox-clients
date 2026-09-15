// Ambient declarations for JSON.rawJSON / JSON.isRawJSON / the reviver's
// third `context` argument. These are part of the JSON.parse source-access
// proposal (Stage 3), implemented in Node >=21 and stable enough to depend
// on at the >=22.12 floor this driver requires, but TypeScript's lib.es5.d.ts
// does not declare them as of TypeScript 5.9.3 (confirmed against the
// devcontainer's installed version). Remove this file once @types/node or
// TypeScript's own lib ships them upstream.

interface JsonReviverContext {
  readonly source?: string;
}

interface RawJSON {
  readonly rawJSON: string;
}

interface JSON {
  rawJSON(text: string): RawJSON;
  isRawJSON(value: unknown): value is RawJSON;

  parse(
    text: string,
    reviver?: (
      this: unknown,
      key: string,
      value: unknown,
      context: JsonReviverContext,
    ) => unknown,
  ): unknown;
}
