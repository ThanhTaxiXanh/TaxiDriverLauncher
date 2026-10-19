import { resolve } from "node:path";
// vite-plus re-exports Vite's defineConfig with the Vite+ blocks (test, lint)
// typed; `vp dev` / `vp build` / `vp test` / `vp lint` / `vp check` all read
// this one file.
import { defineConfig } from "vite-plus";

export default defineConfig({
    // Relative asset URLs: the page is served from
    // https://appassets.androidplatform.net/assets/web/index.html, so absolute
    // "/assets/..." URLs would escape the web/ asset subtree.
    base: "./",
    build: {
        // The lowest WebView an Android 13 device ships with: AOSP's prebuilt
        // for the initial Android 13 release is Chromium 101 (later 13 point
        // releases carry 109), and aftermarket AI boxes build their firmware
        // from that AOSP branch with the AOSP WebView package, which the Play
        // Store does not update — so never emit newer syntax. Mirrored by
        // WEBMAP_CHROMIUM_FLOOR in the diagnostics (WebViewFactsCollector.kt).
        target: "chrome101",
        // dist/web/ mirrors the assets/web/ layout the Kotlin host expects; the
        // whole dist/ directory is wired into the Android assets source set.
        outDir: "dist/web",
        rollupOptions: {
            // One entry page for all backends; main.ts resolves ?backend= and
            // dynamic-imports the matching module, which Vite code-splits into
            // per-backend chunks.
            input: resolve(import.meta.dirname, "index.html"),
        },
    },
    test: {
        // style.ts is pure data transformation; no DOM environment needed.
        environment: "node",
        include: ["src/**/*.test.ts"],
    },
    lint: {
        // Oxlint settings live here, not in an .oxlintrc.json — `vp lint` /
        // `vp check` read only this block by default, and the Vite+ docs
        // recommend the single config home. Parity with the retired Biome
        // setup: the recommended preset maps to the correctness + suspicious
        // categories, noVar/useConst map to the eslint core rules below, and
        // the custom let ban moves from the Biome GritQL plugin (no-let.grit)
        // to the no-let.js Oxlint JS plugin (alpha API, dev-time only —
        // nothing from it ships).
        ignorePatterns: ["dist/**"],
        jsPlugins: ["./no-let.js"],
        categories: {
            correctness: "error",
            suspicious: "error",
        },
        // options.typeAware adds the type-aware rules of the enabled
        // categories (e.g. no-floating-promises, no-unsafe-type-assertion),
        // run by tsgolint on TypeScript 7; vite-plus ships tsgolint with its
        // Oxlint. Fix a finding in code rather than suppressing it: casts at
        // the window/bridge and test-double boundaries became declarations,
        // type guards and narrowed signatures. options.typeCheck is a separate
        // switch and stays off: type checking remains the explicit
        // `tsc --noEmit` in the check script (the shape `vp migrate`
        // generates).
        options: { typeAware: true },
        rules: {
            "eslint/no-var": "error",
            "eslint/prefer-const": "error",
            "femto/no-let": "error",
        },
    },
});
