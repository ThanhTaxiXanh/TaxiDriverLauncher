// docs/src/catalog/schema.ts
// The catalog manifest's schema version and runtime shape checks, shared by
// the Node import script (scripts/import-catalog.ts) and the browser island
// (CatalogViewer.tsx). They live in their own module rather than manifest.ts
// (type-only re-exports) or import-catalog.ts itself, which imports `sharp`
// and must never reach the browser bundle.
import type {
    Axis,
    AxisValue,
    SiteEntry,
    SiteManifest,
} from "../../scripts/import-catalog.ts";

export const CATALOG_SCHEMA_VERSION = 1;

// Both manifests arrive as JSON (the generator's manifest.json read by the
// import script, the site manifest fetched by the island), so their shape is
// checked here instead of asserted: a malformed file then fails with a clear
// error at the boundary, not as an undefined-property crash deep in the
// matrix code.
export const isRecord = (value: unknown): value is Record<string, unknown> =>
    typeof value === "object" && value !== null && !Array.isArray(value);

export const isString = (value: unknown): value is string =>
    typeof value === "string";

export const isNumber = (value: unknown): value is number =>
    typeof value === "number";

export const isArrayOf = <T>(
    value: unknown,
    isItem: (item: unknown) => item is T,
): value is T[] => Array.isArray(value) && value.every(isItem);

export const isStringRecord = (
    value: unknown,
): value is Record<string, string> =>
    isRecord(value) && Object.values(value).every(isString);

const isAxisValue = (value: unknown): value is AxisValue =>
    isRecord(value) && isString(value.id) && isString(value.label);

export const isAxis = (value: unknown): value is Axis =>
    isRecord(value) &&
    isString(value.id) &&
    isString(value.label) &&
    isArrayOf(value.values, isAxisValue);

const isSiteEntry = (value: unknown): value is SiteEntry =>
    isRecord(value) &&
    isString(value.id) &&
    isNumber(value.widthDp) &&
    isNumber(value.heightDp) &&
    isStringRecord(value.values) &&
    isString(value.full) &&
    isString(value.thumb) &&
    isNumber(value.widthPx) &&
    isNumber(value.heightPx);

export const isSiteManifest = (value: unknown): value is SiteManifest =>
    isRecord(value) &&
    value.schemaVersion === CATALOG_SCHEMA_VERSION &&
    isString(value.generatedAt) &&
    isString(value.gitSha) &&
    isNumber(value.thumbWidth) &&
    isArrayOf(value.axes, isAxis) &&
    isArrayOf(value.entries, isSiteEntry);
